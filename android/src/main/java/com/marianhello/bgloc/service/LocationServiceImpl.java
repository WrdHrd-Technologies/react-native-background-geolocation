package com.marianhello.bgloc.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.ConnectivityListener;
import com.marianhello.bgloc.Setting;
import com.marianhello.bgloc.sync.LocationSyncWorker;
import com.marianhello.bgloc.sync.NotificationHelper;
import com.marianhello.bgloc.PluginException;
import com.marianhello.bgloc.PostLocationTask;
import com.marianhello.bgloc.ResourceResolver;
import com.marianhello.bgloc.data.BackgroundActivity;
import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.bgloc.data.LocationTransform;
import com.marianhello.bgloc.headless.AbstractTaskRunner;
import com.marianhello.bgloc.headless.ActivityTask;
import com.marianhello.bgloc.headless.LocationTask;
import com.marianhello.bgloc.headless.StationaryTask;
import com.marianhello.bgloc.headless.Task;
import com.marianhello.bgloc.headless.TaskRunner;
import com.marianhello.bgloc.headless.TaskRunnerFactory;
import com.marianhello.bgloc.provider.LocationProvider;
import com.marianhello.bgloc.provider.LocationProviderFactory;
import com.marianhello.bgloc.provider.ProviderDelegate;
import com.marianhello.logging.LoggerManager;
import com.marianhello.logging.UncaughtExceptionLogger;
import com.wrdhrd.bgloc.provider.FusedDistanceFilterLocationProvider;

import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.containsCommand;
import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.containsMessage;
import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.getCommand;
import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.getMessage;

public class LocationServiceImpl extends Service implements ProviderDelegate, LocationService {

    public static final String ACTION_BROADCAST = ".broadcast";
    public static final int MSG_ON_ERROR = 100;
    public static final int MSG_ON_LOCATION = 101;
    public static final int MSG_ON_STATIONARY = 102;
    public static final int MSG_ON_ACTIVITY = 103;
    public static final int MSG_ON_SERVICE_STARTED = 104;
    public static final int MSG_ON_SERVICE_STOPPED = 105;
    public static final int MSG_ON_ABORT_REQUESTED = 106;
    public static final int MSG_ON_HTTP_AUTHORIZATION = 107;

    private static final int NOTIFICATION_ID = 1;
    private static final int PERMISSION_NOTIFICATION_ID = 2;

    private ResourceResolver mResolver;
    private Config mConfig;
    private Setting mSetting;
    private LocationProvider mProvider;
    private org.slf4j.Logger logger;

    private final IBinder mBinder = new LocalBinder();
    private HandlerThread mPipelineThread;
    private PipelineHandler mPipelineHandler;
    
    private LocationDAO mLocationDAO;
    private PostLocationTask mPostLocationTask;
    private String mHeadlessTaskRunnerClass;
    private TaskRunner mHeadlessTaskRunner;

    private long mServiceId = -1;
    private static boolean sIsRunning = false;
    private boolean mIsInForeground = false;

    private static LocationTransform sLocationTransform;
    private static LocationProviderFactory sLocationProviderFactory;
    
    private PowerManager.WakeLock wakeLock;
    private final Handler mWatchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable mWatchdogRunnable;
    private static final long WATCHDOG_TIMEOUT = 5 * 60 * 1000; 

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private static int sActiveBindCount = 0;
    private static long lastNetworkSyncTime = 0;

    private static class PipelineMsg {
        static final int PROCESS_INTENT = 1001;
        static final int FORCE_HIGH_GEAR = 1002;
        static final int ASYNC_CONFIGURE = 1003;
    }

    private class PipelineHandler extends Handler {
        public PipelineHandler(@NonNull Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case PipelineMsg.PROCESS_INTENT:
                    handleIntentOnPipeline((Intent) msg.obj);
                    break;
                case PipelineMsg.FORCE_HIGH_GEAR:
                    if (mProvider instanceof FusedDistanceFilterLocationProvider) {
                        logger.warn("Pipeline shifting tracking engine to high gear via asynchronous worker channel.");
                        ((FusedDistanceFilterLocationProvider) mProvider).forceHighGear();
                    }
                    break;
                case PipelineMsg.ASYNC_CONFIGURE:
                    handleConfigureOnPipeline((Config) msg.obj);
                    break;
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning = false;
        UncaughtExceptionLogger.register(this);
        logger = LoggerManager.getLogger(LocationServiceImpl.class);

        mServiceId = System.currentTimeMillis();

        mPipelineThread = new HandlerThread("BG_LocationService.Pipeline", Process.THREAD_PRIORITY_BACKGROUND);
        mPipelineThread.start();
        mPipelineHandler = new PipelineHandler(mPipelineThread.getLooper());

        mResolver = ResourceResolver.newInstance(this);
        mLocationDAO = DAOFactory.createLocationDAO(this);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.marianhello.backgroundgeolocation:wakelock");
        }

        mPostLocationTask = new PostLocationTask(mLocationDAO,
                new PostLocationTask.PostLocationTaskListener() {
                    @Override public void onRequestedAbortUpdates() { handleRequestedAbortUpdates(); }
                    @Override public void onHttpAuthorizationUpdates() { handleHttpAuthorizationUpdates(); }
                    @Override public void onSyncRequested() { scheduleNetworkSync(false); }
                }, new ConnectivityListener() {
            @Override public boolean hasConnectivity() { return isNetworkAvailable(); }
        });

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager != null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    logger.info("Network became available");
                    if (mPostLocationTask != null) {
                        mPostLocationTask.setHasConnectivity(true);
                    }
                    scheduleNetworkSync(true);
                }

                @Override
                public void onLost(@NonNull Network network) {
                    logger.info("Network lost");
                    if (mPostLocationTask != null) {
                        mPostLocationTask.setHasConnectivity(false);
                    }
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                    boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    if (mPostLocationTask != null) {
                        mPostLocationTask.setHasConnectivity(hasInternet);
                    }
                }
            };
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        }
        
        NotificationHelper.registerServiceChannel(this);
    }

    private void promoteToForegroundSynchronously() {
        if (mIsInForeground) return;
       
        Setting setting = getSetting(); 
        if (setting != null && !setting.isStarted()) {
            logger.info("Synchronous promotion bypassed: System settings indicate tracking is toggled OFF.");
            return; 
        }

        try {
            Config fastConfig = getConfig();
            Notification notification = new NotificationHelper.NotificationFactory(this).getNotification(
                    fastConfig.getNotificationTitle(),
                    fastConfig.getNotificationText(),
                    fastConfig.getLargeNotificationIcon(),
                    fastConfig.getSmallNotificationIcon(),
                    fastConfig.getNotificationIconColor());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { 
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            mIsInForeground = true;
        } catch (Exception e) {
            logger.error("Failed foreground allocation initialization sequence.", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && LocationServiceIntentBuilder.containsCommand(intent)) {
            promoteToForegroundSynchronously();
        } else {
            logger.info("Ghost execution intent detected. Skipping immediate foreground promotion.");
        }

        Message message = mPipelineHandler.obtainMessage(PipelineMsg.PROCESS_INTENT, intent);
        mPipelineHandler.sendMessage(message);

        return START_STICKY;
    }

    private void handleIntentOnPipeline(@Nullable Intent intent) {
        Setting setting = getSetting();
        if (setting == null || !setting.isStarted()) {
            logger.warn("Pipeline: Confirmed tracking is disabled. Dismantling ghost service context.");
            
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    // 🛡️ MEMORY SHIELD: Purge all timers and locks before tearing down process context
                    if (mWatchdogRunnable != null) {
                        mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
                    }
                    safelyReleaseWakeLock();
                    stopForeground(true); 
                    stopSelf();           
                }
            });
            return; 
        }

        if (intent == null || !containsCommand(intent)) {
            start();
            return;
        }

        LocationServiceIntentBuilder.Command cmd = getCommand(intent);
        int commandId = cmd.getId();
        logger.debug(String.format("Processing intent on pipeline. Running state: [%s]. cmdId: [%d]", sIsRunning ? "STARTED" : "NOT STARTED", commandId));

        processCommand(commandId, cmd.getArgument());

        if (containsMessage(intent)) {
            processMessage(getMessage(intent));
        }
    }

    private void processCommand(int command, Object arg) {
        try {
            switch (command) {
                case CommandId.START: start(); break;
                case CommandId.START_FOREGROUND_SERVICE: startForegroundService(); break;
                case CommandId.STOP: stop(); break;
                case CommandId.CONFIGURE: configure((Config) arg); break;
                case CommandId.STOP_FOREGROUND: stopForeground(); break;
                case CommandId.START_FOREGROUND: startForeground(); break;
                case CommandId.REGISTER_HEADLESS_TASK: registerHeadlessTask((String) arg); break;
                case CommandId.START_HEADLESS_TASK: startHeadlessTask(); break;
                case CommandId.STOP_HEADLESS_TASK: stopHeadlessTask(); break;
            }
        } catch (Exception e) {
            logger.error("processCommand exception: ", e);
        }
    }

    @Override
    public synchronized void start() {
        if (sIsRunning) return;

        if (mSetting == null) mSetting = getSetting(); 
        if (!mSetting.isStarted()) {
            sIsRunning = false;
            return;
        }
        if (mConfig == null) mConfig = getConfig(); 

        logger.debug("Starting location updates provider: {}", mConfig.toString());

        mPostLocationTask.setConfig(mConfig);
        mPostLocationTask.clearQueue();

        LocationProviderFactory spf = sLocationProviderFactory != null
                ? sLocationProviderFactory : new LocationProviderFactory(LocationServiceImpl.this);
        
        mProvider = spf.getInstance(mConfig.getLocationProvider());
        mProvider.setDelegate(LocationServiceImpl.this);
        
        mProvider.onCreate();
        mProvider.onConfigure(mConfig);
        
        try {
            mProvider.onStart();
            sIsRunning = true;
            logger.info("Location provider started successfully on pipeline.");

            resetWatchdogTimer();
        } catch (Exception e) {
            logger.error("Failed to start location provider", e);
        }

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_SERVICE_STARTED);
        bundle.putLong("serviceId", mServiceId);
        broadcastMessage(bundle);
    }

    @Override
    public synchronized void configure(final Config config) {
        Message msg = mPipelineHandler.obtainMessage(PipelineMsg.ASYNC_CONFIGURE, config);
        mPipelineHandler.sendMessage(msg);
    }

    private void handleConfigureOnPipeline(@NonNull Config config) {
        if (mConfig == null) {
            if (config != null) {
                mConfig = config;
            } else {
                try {
                    ConfigurationDAO configDAO = DAOFactory.createConfigurationDAO(LocationServiceImpl.this);
                    mConfig = configDAO.retrieveConfiguration();
                } catch (Exception e) {
                    logger.info("Failed to read configuration database layer cache.", e);
                }
                if (mConfig == null) {
                    mConfig = Config.getDefault();
                }
            }
            if (mPostLocationTask != null) {
                mPostLocationTask.setConfig(mConfig);
            }
        }

        final Config currentConfig = (mConfig != null) ? mConfig : config;
        if (config != null) {
            mConfig = config;
        }
        if (mPostLocationTask != null) {
            mPostLocationTask.setConfig(mConfig);
        }

        if (mSetting == null) mSetting = getSetting();
        if (!mSetting.isStarted()) sIsRunning = false;

        if (sIsRunning) {
            boolean currentStartForeground = (currentConfig != null && currentConfig.getStartForeground() != null)
                    ? currentConfig.getStartForeground() : true;
            boolean newStartForeground = (mConfig.getStartForeground() != null)
                    ? mConfig.getStartForeground() : true;

            if (currentStartForeground && !newStartForeground) {
                new Handler(Looper.getMainLooper()).post(() -> stopForeground(true));
            }

            if (newStartForeground) {
                if (!currentStartForeground) {
                    new Handler(Looper.getMainLooper()).post(this::startForeground);
                } else {
                    Notification notification = new NotificationHelper.NotificationFactory(LocationServiceImpl.this).getNotification(
                            mConfig.getNotificationTitle(),
                            mConfig.getNotificationText(),
                            mConfig.getLargeNotificationIcon(),
                            mConfig.getSmallNotificationIcon(),
                            mConfig.getNotificationIconColor());

                    NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (notificationManager != null) {
                        notificationManager.notify(NOTIFICATION_ID, notification);
                        notificationManager.cancel(PERMISSION_NOTIFICATION_ID);
                    }
                }
            }
        }

        int currentProviderType = (currentConfig != null) ? currentConfig.getLocationProvider() : -1;

        if (currentProviderType != mConfig.getLocationProvider()) {
            boolean shouldStart = mProvider != null && mProvider.isStarted();
            if (mProvider != null) {
                mProvider.onDestroy();
            }
            LocationProviderFactory spf = new LocationProviderFactory(LocationServiceImpl.this);
            mProvider = spf.getInstance(mConfig.getLocationProvider());
            mProvider.setDelegate(LocationServiceImpl.this);
            mProvider.onCreate();
            mProvider.onConfigure(mConfig);
            if (shouldStart) {
                mProvider.onStart();
            }
        } else if (mProvider != null) {
            mProvider.onConfigure(mConfig);
        }
    }

    @Override
    public void onLocation(BackgroundLocation location) {
        if (wakeLock != null) {
            wakeLock.acquire(15000); 
            logger.debug("Power Optimization: Pulsed WakeLock engaged for 15 seconds.");
        }
        resetWatchdogTimer();

        logger.debug("New location received: {}", location.toString());
        location = transformLocation(location);
        if (location == null) {
            safelyReleaseWakeLock();
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_LOCATION);
        bundle.putParcelable("payload", location);
        broadcastMessage(bundle);

        runHeadlessTask(new LocationTask(location) {
            @Override public void onError(String err) { logger.error("Headless location task error: {}", err); }
            @Override public void onResult(String res) { logger.debug("Headless location task result: {}", res); }
        });

        postLocation(location);
        safelyReleaseWakeLock();
    }

    @Override
    public void onStationary(BackgroundLocation location) {
        if (wakeLock != null) {
            wakeLock.acquire(15000); 
        }
        resetWatchdogTimer();

        logger.debug("New stationary heartbeat ping received natively: {}", location.toString());
        location = transformLocation(location);
        if (location == null) {
            safelyReleaseWakeLock();
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_STATIONARY);
        bundle.putParcelable("payload", location);
        broadcastMessage(bundle);

        runHeadlessTask(new StationaryTask(location){
            @Override public void onError(String err) { logger.error("Headless stationary task error: {}", err); }
            @Override public void onResult(String res) { logger.debug("Headless stationary task result: {}", res); }
        });

        postLocation(location);
        scheduleNetworkSync(true); 

        safelyReleaseWakeLock();
    }

    @Override
    public void onActivity(BackgroundActivity activity) {
        logger.debug("New activity update: {}", activity.toString());

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_ACTIVITY);
        bundle.putParcelable("payload", activity);
        broadcastMessage(bundle);

        runHeadlessTask(new ActivityTask(activity){
            @Override public void onError(String err) { logger.error("Headless activity task error: {}", err); }
            @Override public void onResult(String res) { logger.debug("Headless activity task result: {}", res); }
        });
    }

    @Override
    public void onError(PluginException error) {
        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_ERROR);
        bundle.putBundle("payload", error.toBundle());
        broadcastMessage(bundle);
        mPostLocationTask.add(error);

        if (error.getCode() == PluginException.PERMISSION_DENIED_ERROR) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(LocationServiceImpl.this, NotificationHelper.ANDROID_PERMISSIONS_CHANNEL_ID)
                    .setContentTitle("Permission Denied")
                    .setContentText("Location access is denied. Dynamic asset tracking requires explicit background authorization.")
                    .setSmallIcon(android.R.drawable.ic_dialog_info);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(PERMISSION_NOTIFICATION_ID, builder.build());
        }
    }

    private void scheduleNetworkSync(boolean forceImmediate) {
        logger.warn("scheduleNetworkSync(force=" + forceImmediate + ")");

        long now = SystemClock.elapsedRealtime();
        if (now - lastNetworkSyncTime > 30_000) {
            lastNetworkSyncTime = now;
            scheduleNetworkSync(true);
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        Data inputData = new Data.Builder()
                .putBoolean("force_sync", forceImmediate)
                .build();

        OneTimeWorkRequest syncRequest = new OneTimeWorkRequest.Builder(LocationSyncWorker.class)
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(inputData)
                .build();

        Config config = getConfig();
        ExistingWorkPolicy optimalPolicy = ExistingWorkPolicy.KEEP; 
        
        if (config.getUrl() != null && !config.getUrl().isEmpty()) {
            optimalPolicy = ExistingWorkPolicy.REPLACE; 
            logger.debug("Real-time URL detected: Utilizing REPLACE policy to ensure immediate delivery.");
        } else {
            logger.debug("Batch syncUrl detected: Utilizing KEEP policy to optimize battery lifecycle.");
        }

        WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
                "LocationSyncJob",
                optimalPolicy,
                syncRequest
        );
    }

    @Override
    public void onDestroy() {
        logger.info("Destroying LocationServiceImpl clean-up stack active.");

        if (mWatchdogRunnable != null) {
            mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
        }
        safelyReleaseWakeLock();

        if (mProvider != null) mProvider.onDestroy();
        if (mPipelineThread != null) mPipelineThread.quitSafely();
        if (mPostLocationTask != null) mPostLocationTask.shutdown();

        try {
            if (connectivityManager != null && networkCallback != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception e) { /* no-op */ }
        
        sIsRunning = false;
        super.onDestroy();
    }

    @Override public synchronized void startForegroundService() { start(); }
    
    @Override public synchronized void stop() {
        if (!sIsRunning) return;

        if (mWatchdogRunnable != null) {
            mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
            logger.info("Watchdog timer disarmed cleanly.");
        }
        safelyReleaseWakeLock();

        if (mProvider != null) mProvider.onStop();
        stopForeground(true);
        stopSelf();
        broadcastMessage(MSG_ON_SERVICE_STOPPED);
        sIsRunning = false;
    }

    @Override public void startForeground() {
        if (sIsRunning && !mIsInForeground) {
            Config config = getConfig();
            Notification notification = new NotificationHelper.NotificationFactory(this).getNotification(
                    config.getNotificationTitle(), config.getNotificationText(),
                    config.getLargeNotificationIcon(), config.getSmallNotificationIcon(),
                    config.getNotificationIconColor());
            if (mProvider != null) mProvider.onCommand(LocationProvider.CMD_SWITCH_MODE, LocationProvider.FOREGROUND_MODE);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
                mIsInForeground = true;
            } catch (Exception e) { logger.error("Foreground Error: ", e); }
        }
    }

    @Override public synchronized void stopForeground() {
        if (sIsRunning && mIsInForeground) {
            stopForeground(true);
            if (mProvider != null) mProvider.onCommand(LocationProvider.CMD_SWITCH_MODE, LocationProvider.BACKGROUND_MODE);
            mIsInForeground = false;
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        Config config = getConfig();
        Setting setting = getSetting();
        if (config.getStopOnTerminate() || !setting.isStarted()) {
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        sActiveBindCount++;
        logger.debug("Client binds to service. Active bind count: " + sActiveBindCount);
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        sActiveBindCount++;
        logger.debug("Client rebinds to service. Active bind count: " + sActiveBindCount);
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (sActiveBindCount > 0) {
            sActiveBindCount--;
        }
        logger.debug("Client unbinds from service. Active bind count: " + sActiveBindCount);
        return true; 
    }

    private void processMessage(String message) {}
    @Override public void setting(Setting setting) { mSetting = setting; }
    @Override public synchronized void registerHeadlessTask(String r) { mHeadlessTaskRunnerClass = r; }
    
    @Override public synchronized void startHeadlessTask() {
        if (mHeadlessTaskRunnerClass != null) {
            try {
                mHeadlessTaskRunner = new TaskRunnerFactory().getTaskRunner(mHeadlessTaskRunnerClass);
                ((AbstractTaskRunner) mHeadlessTaskRunner).setContext(this);
            } catch (Exception e) { logger.error("Headless start failed: ", e); }
        }
    }

    @Override public synchronized void stopHeadlessTask() { mHeadlessTaskRunner = null; }
    
    @Override public synchronized void executeProviderCommand(final int c, final int a1) {
        if (mProvider == null) return;
        mPipelineHandler.post(() -> mProvider.onCommand(c, a1));
    }

    private void broadcastMessage(int msgId) { Bundle b = new Bundle(); b.putInt("action", msgId); broadcastMessage(b); }
    
    private void broadcastMessage(Bundle bundle) {
        Intent intent = new Intent(ACTION_BROADCAST);
        intent.putExtras(bundle);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    @Override public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return super.registerReceiver(r, f, null, mPipelineHandler, Context.RECEIVER_NOT_EXPORTED);
        } else {
            return super.registerReceiver(r, f, null, mPipelineHandler);
        }
    }

    @Override public void unregisterReceiver(BroadcastReceiver receiver) { try { super.unregisterReceiver(receiver); } catch (Exception e) {} }
    
    public Config getConfig() {
        if (mConfig != null) return mConfig;
        try { mConfig = DAOFactory.createConfigurationDAO(this).retrieveConfiguration(); } catch (Exception e) {}
        if (mConfig == null) mConfig = Config.getDefault();
        return mConfig;
    }

    public Setting getSetting() {
        if (mSetting != null) return mSetting;
        try { mSetting = DAOFactory.createSettingDAO(this).retrieveSetting(); } catch (Exception e) {}
        if (mSetting == null) mSetting = Setting.getDefault();
        return mSetting;
    }

    private void runHeadlessTask(Task t) { if (mHeadlessTaskRunner != null) mHeadlessTaskRunner.runTask(t); }
    private BackgroundLocation transformLocation(BackgroundLocation l) { return (sLocationTransform != null) ? sLocationTransform.transformLocationBeforeCommit(this, l) : l; }
    private void postLocation(BackgroundLocation l) { mPostLocationTask.add(l); }
    public void handleRequestedAbortUpdates() { broadcastMessage(MSG_ON_ABORT_REQUESTED); }
    public void handleHttpAuthorizationUpdates() { broadcastMessage(MSG_ON_HTTP_AUTHORIZATION); }
    public long getServiceId() { return mServiceId; }
    public boolean isBound() { return new LocationServiceInfoImpl(this).isBound(); }
    public static boolean isRunning() { return sIsRunning; }
    public static void setLocationTransform(@Nullable LocationTransform t) { sLocationTransform = t; }
    public static @Nullable LocationTransform getLocationTransform() { return sLocationTransform; }
    public static void setLocationProviderFactory(LocationProviderFactory f) { sLocationProviderFactory = f; }
    public static boolean isServiceBoundToClient() { return sIsRunning && (sActiveBindCount > 0);}

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        Network network = cm.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /**
     * WATCHDOG ENFORCEMENT ENGINE
     * Runs completely on existing UI Looper threads to guarantee 0% extra battery idle footprint.
     */
    private void resetWatchdogTimer() {
        if (mWatchdogRunnable != null) {
            mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
        }

        mWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (LocationServiceImpl.this) {
                    if (sIsRunning && mProvider != null) {
                        logger.warn("⚠️ Watchdog Triggered: No GPS ticks for 5 minutes during drive. Recycling hardware binders!");
                        try {
                            mProvider.onStop();
                            mProvider.onDestroy();
                            
                            LocationProviderFactory spf = new LocationProviderFactory(LocationServiceImpl.this);
                            mProvider = spf.getInstance(getConfig().getLocationProvider());
                            mProvider.setDelegate(LocationServiceImpl.this);
                            mProvider.onCreate();
                            mProvider.onConfigure(getConfig());
                            mProvider.onStart();
                            
                            logger.info("Watchdog: Hard reset complete. Binders recovered smoothly.");
                        } catch (Exception e) {
                            logger.error("Watchdog: Failed to forcefully recycle hardware provider context", e);
                        }
                        
                        mWatchdogHandler.postDelayed(this, WATCHDOG_TIMEOUT);
                    }
                }
            }
        };

        mWatchdogHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TIMEOUT);
    }

    private void safelyReleaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                logger.debug("Power Optimization: Pulsed WakeLock released cleanly.");
            }
        } catch (Exception e) {
            
        }
    }

    private final BroadcastReceiver connectivityChangeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean hasConnectivity = isNetworkAvailable();
            mPostLocationTask.setHasConnectivity(hasConnectivity);
        }
    };

    public class LocalBinder extends Binder { public LocationServiceImpl getService() { return LocationServiceImpl.this; } }
}
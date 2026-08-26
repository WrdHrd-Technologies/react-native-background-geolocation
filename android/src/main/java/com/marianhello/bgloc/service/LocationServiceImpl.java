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
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.ConnectivityListener;
import com.marianhello.bgloc.Setting;
import com.marianhello.bgloc.data.SettingDAO;
import com.marianhello.bgloc.sync.LocationSyncWorker;
import com.marianhello.bgloc.sync.NotificationHelper;
import com.marianhello.bgloc.PluginException;
import com.marianhello.bgloc.PostLocationTask;
import com.marianhello.bgloc.ResourceResolver;
import com.marianhello.bgloc.data.BackgroundActivity;
import com.marianhello.bgloc.data.BackgroundLocation;
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

    private static final int NOTIFICATION_ID = 1099;
    private static final int PERMISSION_NOTIFICATION_ID = 1098;

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
    private static volatile boolean sIsRunning = false;
    private boolean mIsInForeground = false;

    private static LocationTransform sLocationTransform;
    private static LocationProviderFactory sLocationProviderFactory;

    private PowerManager.WakeLock wakeLock;
    private final Handler mWatchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable mWatchdogRunnable;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private static int sActiveBindCount = 0;
    private static long lastNetworkSyncTime = 0;

    private static class PipelineMsg {
        static final int PROCESS_INTENT = 1001;
        static final int FORCE_HIGH_GEAR = 1002;
        static final int ASYNC_CONFIGURE = 1003;
        static final int RECYCLE_PROVIDER = 1004;
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
                        logger.warn("Pipeline shifting tracking engine to high gear.");
                        ((FusedDistanceFilterLocationProvider) mProvider).forceHighGear();
                    } else if (mProvider != null) {
                        mProvider.onCommand(LocationProvider.CMD_SWITCH_MODE, LocationProvider.FOREGROUND_MODE);
                    }
                    break;
                case PipelineMsg.ASYNC_CONFIGURE:
                    handleConfigureOnPipeline((Config) msg.obj);
                    break;
                case PipelineMsg.RECYCLE_PROVIDER:
                    handleRecycleProviderOnPipeline();
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

        NotificationHelper.registerAllChannels(this);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.marianhello.backgroundgeolocation:wakelock");
            wakeLock.setReferenceCounted(false);
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
                    logger.info("Network connectivity confirmed active.");
                    if (mPostLocationTask != null) mPostLocationTask.setHasConnectivity(true);
                    scheduleNetworkSync(true);
                }

                @Override
                public void onLost(@NonNull Network network) {
                    logger.info("Network connectivity dropped.");
                    if (mPostLocationTask != null) mPostLocationTask.setHasConnectivity(false);
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                    boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if (mPostLocationTask != null) mPostLocationTask.setHasConnectivity(hasInternet);
                }
            };
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    connectivityManager.registerDefaultNetworkCallback(networkCallback);
                }
            } catch (Exception e) {
                logger.error("Failed registering network callback observer", e);
            }
        }
    }

    private Notification buildNotification(Config config) {
        NotificationHelper.registerAllChannels(this);
        NotificationHelper.NotificationFactory factory = new NotificationHelper.NotificationFactory(this);
        return factory.getServiceNotification(config);
    }

    private void promoteToForegroundSynchronously() {
        if (mIsInForeground) return;

        Setting setting = getSetting();
        if (setting != null && !setting.isStarted()) {
            logger.info("Promotion bypassed: Settings indicate tracking is toggled OFF.");
            return;
        }

        try {
            Config config = getConfig();
            Notification notification = buildNotification(config);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            mIsInForeground = true;
        } catch (Exception e) {
            logger.error("Foreground initialization sequence failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && containsCommand(intent)) {
            promoteToForegroundSynchronously();
        } else {
            logger.info("Ghost execution intent detected. Forwarding to pipeline validation.");
        }

        Message message = mPipelineHandler.obtainMessage(PipelineMsg.PROCESS_INTENT, intent);
        mPipelineHandler.sendMessage(message);

        return START_STICKY;
    }

    private void handleIntentOnPipeline(@Nullable Intent intent) {
        Setting setting = getSetting();
        if (setting == null || !setting.isStarted()) {
            logger.warn("Pipeline: Tracking disabled in SQLite DAO. Terminating ghost execution context.");
            new Handler(Looper.getMainLooper()).post(() -> {
                if (mWatchdogRunnable != null) {
                    mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
                }
                safelyReleaseWakeLock();
                stopForeground(true);
                stopSelf();
            });
            return;
        }

        if (intent == null || !containsCommand(intent)) {
            start();
            return;
        }

        LocationServiceIntentBuilder.Command cmd = getCommand(intent);
        int commandId = cmd.getId();
        logger.debug("Processing intent on pipeline. Running: [{}]. cmdId: [{}]", sIsRunning ? "STARTED" : "STOPPED", commandId);

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
            logger.error("processCommand runtime failure: ", e);
        }
    }

    @Override
    public synchronized void start() {
        if (sIsRunning) return;

        try {
            SettingDAO settingDAO = DAOFactory.createSettingDAO(this);
            Setting setting = settingDAO.retrieveSetting();
            if (setting == null) setting = new Setting();
            setting.setStarted(true);
            setting.setUpdatedAt(System.currentTimeMillis());
            settingDAO.persistSetting(setting);
            mSetting = setting;
            logger.info("Persisted isStarted = true to SQLite.");
        } catch (Exception e) {
            logger.error("Failed to persist started state to SQLite", e);
        }

        if (mConfig == null) mConfig = getConfig();

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
            logger.info("Location provider started cleanly on background pipeline.");
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
        if (config != null) mConfig = config;
        if (mPostLocationTask != null) mPostLocationTask.setConfig(mConfig);

        if (mSetting == null) mSetting = getSetting();

        if (mProvider != null) {
            mProvider.onConfigure(mConfig);
        }

        if (mIsInForeground) {
            Notification notification = buildNotification(mConfig);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        }
    }

    @Override
    public void onLocation(BackgroundLocation location) {
        acquireTransientWakeLock(5000);
        resetWatchdogTimer();

        location = transformLocation(location);
        if (location == null) return;

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_LOCATION);
        bundle.putParcelable("payload", location);
        broadcastMessage(bundle);

        runHeadlessTask(new LocationTask(location) {
            @Override public void onError(String err) { logger.error("Headless location error: {}", err); }
            @Override public void onResult(String res) {}
        });

        postLocation(location);
    }

    @Override
    public void onStationary(BackgroundLocation location) {
        acquireTransientWakeLock(5000);
        resetWatchdogTimer();

        location = transformLocation(location);
        if (location == null) return;

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_STATIONARY);
        bundle.putParcelable("payload", location);
        broadcastMessage(bundle);

        runHeadlessTask(new StationaryTask(location) {
            @Override public void onError(String err) { logger.error("Headless stationary error: {}", err); }
            @Override public void onResult(String res) {}
        });

        postLocation(location);
        scheduleNetworkSync(true);
    }

    @Override
    public void onActivity(BackgroundActivity activity) {
        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_ACTIVITY);
        bundle.putParcelable("payload", activity);
        broadcastMessage(bundle);

        runHeadlessTask(new ActivityTask(activity) {
            @Override public void onError(String err) { logger.error("Headless activity error: {}", err); }
            @Override public void onResult(String res) {}
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
            NotificationHelper.registerAllChannels(this);
            NotificationHelper.NotificationFactory factory = new NotificationHelper.NotificationFactory(this);
            Notification notification = factory.getPermissionDeniedNotification(
                    "Permission Denied",
                    "Location access is denied. Asset tracking requires explicit background authorization."
            );

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(PERMISSION_NOTIFICATION_ID, notification);
            }
        }
    }

    private void scheduleNetworkSync(boolean forceImmediate) {
        long now = SystemClock.elapsedRealtime();
        if (!forceImmediate && (now - lastNetworkSyncTime < 15_000L)) return;
        if (forceImmediate && (now - lastNetworkSyncTime < 5_000L)) return;
        lastNetworkSyncTime = now;

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        Data inputData = new Data.Builder()
                .putBoolean("force_sync", forceImmediate)
                .build();

        OneTimeWorkRequest syncRequest = new OneTimeWorkRequest.Builder(LocationSyncWorker.class)
                .setConstraints(constraints)
                .setInputData(inputData)
                .build();

        Config config = getConfig();
        ExistingWorkPolicy optimalPolicy = (config.getUrl() != null && !config.getUrl().isEmpty())
                ? ExistingWorkPolicy.REPLACE : ExistingWorkPolicy.KEEP;

        WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
                "LocationSyncJob",
                optimalPolicy,
                syncRequest
        );
    }

    @Override
    public void onDestroy() {
        logger.info("Destroying LocationServiceImpl cleanup stack.");

        if (mWatchdogRunnable != null) {
            mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
            mWatchdogRunnable = null;
        }
        safelyReleaseWakeLock();

        if (mProvider != null) mProvider.onDestroy();
        if (mPipelineThread != null) mPipelineThread.quitSafely();
        if (mPostLocationTask != null) mPostLocationTask.shutdown();

        try {
            if (connectivityManager != null && networkCallback != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) {}

        sIsRunning = false;
        super.onDestroy();
    }

    @Override public synchronized void startForegroundService() { start(); }

    @Override
    public synchronized void stop() {
        if (!sIsRunning) return;

        logger.info("Stopping LocationServiceImpl...");

        if (mWatchdogRunnable != null) {
            mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
            mWatchdogRunnable = null;
        }
        safelyReleaseWakeLock();

        try {
            SettingDAO settingDAO = DAOFactory.createSettingDAO(this);
            Setting setting = settingDAO.retrieveSetting();
            if (setting == null) setting = new Setting();
            setting.setStarted(false);
            setting.setUpdatedAt(System.currentTimeMillis());
            settingDAO.persistSetting(setting);
            mSetting = setting;
            logger.info("Persisted isStarted = false to SQLite.");
        } catch (Exception e) {
            logger.error("Failed persisting stopped state to SQLite", e);
        }

        try {
            WorkManager.getInstance(getApplicationContext()).cancelUniqueWork("LocationSyncJob");
        } catch (Exception ignored) {}

        if (mProvider != null) mProvider.onStop();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }

        stopSelf();
        broadcastMessage(MSG_ON_SERVICE_STOPPED);
        sIsRunning = false;
    }

    @Override
    public void startForeground() {
        if (sIsRunning && !mIsInForeground) {
            promoteToForegroundSynchronously();
            if (mProvider != null) mProvider.onCommand(LocationProvider.CMD_SWITCH_MODE, LocationProvider.FOREGROUND_MODE);
        }
    }

    @Override
    public synchronized void stopForeground() {
        if (sIsRunning && mIsInForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            if (mProvider != null) mProvider.onCommand(LocationProvider.CMD_SWITCH_MODE, LocationProvider.BACKGROUND_MODE);
            mIsInForeground = false;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Config config = getConfig();
        Setting setting = getSetting();
        if (config.getStopOnTerminate() || !setting.isStarted()) {
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public IBinder onBind(Intent intent) { sActiveBindCount++; return mBinder; }
    @Override public void onRebind(Intent intent) { sActiveBindCount++; super.onRebind(intent); }
    @Override public boolean onUnbind(Intent intent) { if (sActiveBindCount > 0) sActiveBindCount--; return true; }
    private void processMessage(String message) {}

    @Override public void setting(Setting setting) {
        mSetting = setting;
        try { DAOFactory.createSettingDAO(this).persistSetting(setting); } catch (Exception ignored) {}
    }

    @Override public synchronized void registerHeadlessTask(String r) { mHeadlessTaskRunnerClass = r; }
    @Override public synchronized void startHeadlessTask() {
        if (mHeadlessTaskRunnerClass != null) {
            try {
                mHeadlessTaskRunner = new TaskRunnerFactory().getTaskRunner(mHeadlessTaskRunnerClass);
                ((AbstractTaskRunner) mHeadlessTaskRunner).setContext(this);
            } catch (Exception ignored) {}
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

    @Override
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) {
        return ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override public void unregisterReceiver(BroadcastReceiver receiver) { try { super.unregisterReceiver(receiver); } catch (Exception ignored) {} }

    public Config getConfig() {
        if (mConfig != null) return mConfig;
        try { mConfig = DAOFactory.createConfigurationDAO(this).retrieveConfiguration(); } catch (Exception ignored) {}
        if (mConfig == null) mConfig = Config.getDefault();
        return mConfig;
    }

    public Setting getSetting() {
        if (mSetting != null) return mSetting;
        try { mSetting = DAOFactory.createSettingDAO(this).retrieveSetting(); } catch (Exception ignored) {}
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
    public static boolean isServiceBoundToClient() { return sIsRunning && (sActiveBindCount > 0); }

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

    private void resetWatchdogTimer() {
        if (mWatchdogRunnable != null) {
            mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
        }

        Config currentConfig = getConfig();
        long heartbeat = (currentConfig.getHeartbeatInterval() != null && currentConfig.getHeartbeatInterval() > 0)
                ? (currentConfig.getHeartbeatInterval() < 1000 ? currentConfig.getHeartbeatInterval() * 1000L : currentConfig.getHeartbeatInterval())
                : 600000L;

        long watchdogTimeout = Math.max(heartbeat * 2, 300000L);

        mWatchdogRunnable = () -> mPipelineHandler.obtainMessage(PipelineMsg.RECYCLE_PROVIDER).sendToTarget();
        mWatchdogHandler.postDelayed(mWatchdogRunnable, watchdogTimeout);
    }

    private void handleRecycleProviderOnPipeline() {
        if (!sIsRunning || mProvider == null) return;
        try {
            logger.warn("Recycling location provider context on background pipeline...");
            mProvider.onStop();
            mProvider.onDestroy();

            LocationProviderFactory spf = new LocationProviderFactory(LocationServiceImpl.this);
            mProvider = spf.getInstance(getConfig().getLocationProvider());
            mProvider.setDelegate(LocationServiceImpl.this);
            mProvider.onCreate();
            mProvider.onConfigure(getConfig());
            mProvider.onStart();
            logger.info("Watchdog: Hard reset complete. Provider binders cleanly recovered.");
        } catch (Exception e) {
            logger.error("Failed to recycle hardware provider", e);
        }
    }

    private void acquireTransientWakeLock(long timeoutMs) {
        try {
            if (wakeLock != null) {
                wakeLock.acquire(timeoutMs);
                logger.debug("Transient WakeLock held for {} ms.", timeoutMs);
            }
        } catch (Exception ignored) {}
    }

    private void safelyReleaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {}
    }

    public class LocalBinder extends Binder {
        public LocationServiceImpl getService() {
            return LocationServiceImpl.this;
        }
    }
}
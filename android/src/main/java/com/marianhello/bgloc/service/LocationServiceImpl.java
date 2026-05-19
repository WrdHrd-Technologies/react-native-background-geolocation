package com.marianhello.bgloc.service;

import android.content.pm.ServiceInfo;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
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
import com.marianhello.bgloc.data.SettingDAO;
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
import com.wrdhrd.bgloc.ActivityRecognitionReceiver;
import com.wrdhrd.bgloc.provider.FusedDistanceFilterLocationProvider;

import org.chromium.content.browser.ThreadUtils;
import org.json.JSONException;

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

    private static int NOTIFICATION_ID = 1;
    private static int PERMISSION_NOTIFICATION_ID = 2;

    private ResourceResolver mResolver;
    private Config mConfig;
    private Setting mSetting;
    private LocationProvider mProvider;

    private org.slf4j.Logger logger;

    private final IBinder mBinder = new LocalBinder();
    private HandlerThread mHandlerThread;
    private ServiceHandler mServiceHandler;
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

    private class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
        }
    }

    @SuppressLint("WakelockTimeout")
    @Override
    public IBinder onBind(Intent intent) {
        if (isLegacyEngineActive()) {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
                logger.debug("WAKELOCK acquired");
            }
        }

        logger.debug("Client binds to service");
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        logger.debug("Client rebinds to service");
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            logger.debug("WAKELOCK released on UI unbind.");
        }
        
        logger.debug("All clients have been unbound from service");
        return true; 
    }

    @Override
    public void onCreate() {
        super.onCreate();

        sIsRunning = false;
        UncaughtExceptionLogger.register(this);
        logger = LoggerManager.getLogger(LocationServiceImpl.class);

        logger.info("Creating LocationServiceImpl. Monolithic Architecture Active.");

        mServiceId = System.currentTimeMillis();

        if (mHandlerThread == null) {
            mHandlerThread = new HandlerThread("LocationServiceImpl.Thread", Process.THREAD_PRIORITY_BACKGROUND);
        }
        mHandlerThread.start();
        mServiceHandler = new ServiceHandler(mHandlerThread.getLooper());

        mResolver = ResourceResolver.newInstance(this);
        mLocationDAO = DAOFactory.createLocationDAO(this);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"com.marianhello.backgroundgeolocation:wakelock");

        mPostLocationTask = new PostLocationTask(mLocationDAO,
                new PostLocationTask.PostLocationTaskListener() {
                    @Override
                    public void onRequestedAbortUpdates() { handleRequestedAbortUpdates(); }
                    @Override
                    public void onHttpAuthorizationUpdates() { handleHttpAuthorizationUpdates(); }
                    @Override
                    public void onSyncRequested() { scheduleNetworkSync(false); }
                }, new ConnectivityListener() {
            @Override
            public boolean hasConnectivity() { return isNetworkAvailable(); }
        });

        registerReceiver(connectivityChangeReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        NotificationHelper.registerServiceChannel(this);
    }

    @Override
    public void onDestroy() {
        logger.info("Destroying LocationServiceImpl");

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            logger.info("WAKELOCK released");
        }

        if (mProvider != null) {
            mProvider.onDestroy();
        }

        if (mHandlerThread != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                mHandlerThread.quitSafely();
            } else {
                mHandlerThread.quit(); 
            }
        }

        if (mPostLocationTask != null) {
            mPostLocationTask.shutdown();
        }

        unregisterReceiver(connectivityChangeReceiver);
        sIsRunning = false;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        logger.debug("Task has been removed");
        Config config = getConfig();
        Setting setting = getSetting();
        if (config.getStopOnTerminate() || !setting.isStarted()) {
            logger.info("Stopping self");
            stopSelf();
        } else {
            logger.info("Continue running in background");
        }
        super.onTaskRemoved(rootIntent);
    }

    private void promoteToForegroundSynchronously() {
        if (mIsInForeground) return;

        try {
            Config fastConfig = (mConfig != null) ? mConfig : Config.getDefault();
            
            Notification notification = new NotificationHelper.NotificationFactory(this).getNotification(
                    fastConfig.getNotificationTitle(),
                    fastConfig.getNotificationText(),
                    fastConfig.getLargeNotificationIcon(),
                    fastConfig.getSmallNotificationIcon(),
                    fastConfig.getNotificationIconColor());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                super.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                super.startForeground(NOTIFICATION_ID, notification);
            }
            mIsInForeground = true;
            logger.info("Synchronous foreground promotion successful.");
        } catch (Exception e) {
            logger.error("Failed synchronous foreground promotion", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForegroundSynchronously();

        if (intent == null || !containsCommand(intent)) {
            start();
            return START_STICKY;
        }
        

        if (ActivityRecognitionReceiver.ACTION_ACTIVITY_TRANSITION.equals(intent.getAction())) {
            logger.warn("Activity Recognition interrupt received. Forcing engine into HIGH GEAR.");
            
            if (mProvider instanceof FusedDistanceFilterLocationProvider) {
                ((FusedDistanceFilterLocationProvider) mProvider).forceHighGear();
            }
            return START_STICKY;
        }


       LocationServiceIntentBuilder.Command cmd = getCommand(intent);
        int commandId = cmd.getId();
        logger.debug(
                String.format("Service in [%s] state. cmdId: [%d]. startId: [%d]",
                        sIsRunning ? "STARTED" : "NOT STARTED",
                        commandId,
                        startId)
        );

        processCommand(commandId, cmd.getArgument());

        if (containsMessage(intent)) {
            processMessage(getMessage(intent));
        }

        return START_STICKY;
    }

    private void processMessage(String message) {}

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
            logger.error("processCommand: exception", e);
        }
    }

    @Override
    public synchronized void start() {
        if (sIsRunning) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (mSetting == null) mSetting = getSetting(); // SQLite Read
                if (!mSetting.isStarted()) {
                    sIsRunning = false;
                    return;
                }
                if (mConfig == null) mConfig = getConfig(); // SQLite Read

                logger.debug("Will start service with: {}", mConfig.toString());

                mPostLocationTask.setConfig(mConfig);
                mPostLocationTask.clearQueue();

                LocationProviderFactory spf = sLocationProviderFactory != null
                    ? sLocationProviderFactory : new LocationProviderFactory(LocationServiceImpl.this);
                mProvider = spf.getInstance(mConfig.getLocationProvider());
                mProvider.setDelegate(LocationServiceImpl.this);
                mProvider.onCreate();
                mProvider.onConfigure(mConfig);

                sIsRunning = true;

                mServiceHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mProvider.onStart();
                            logger.info("Location provider started successfully.");
                        } catch (Exception e) {
                            logger.error("Failed to start location provider", e);
                        }
                    }
                });

                Bundle bundle = new Bundle();
                bundle.putInt("action", MSG_ON_SERVICE_STARTED);
                bundle.putLong("serviceId", mServiceId);
                broadcastMessage(bundle);
            }
        }).start();


        // if (mSetting == null) {
        //     mSetting = getSetting();
        // }
        // if(!mSetting.isStarted()){
        //     sIsRunning = false;
        //     return;
        // }
        // if (mConfig == null) {
        //     mConfig = getConfig();
        // }

        // logger.debug("Will start service with: {}", mConfig.toString());

        // mPostLocationTask.setConfig(mConfig);
        // mPostLocationTask.clearQueue();

        // LocationProviderFactory spf = sLocationProviderFactory != null
        //     ? sLocationProviderFactory : new LocationProviderFactory(this);
        // mProvider = spf.getInstance(mConfig.getLocationProvider());
        // mProvider.setDelegate(this);
        // mProvider.onCreate();
        // mProvider.onConfigure(mConfig);

        // sIsRunning = true;

        // mServiceHandler.post(new Runnable() {
        //     @Override
        //     public void run() {
        //         try {
        //             mProvider.onStart();
        //             logger.info("Location provider started successfully on background thread.");
        //         } catch (Exception e) {
        //             logger.error("Failed to start location provider", e);
        //         }
        //     }
        // });

        // Bundle bundle = new Bundle();
        // bundle.putInt("action", MSG_ON_SERVICE_STARTED);
        // bundle.putLong("serviceId", mServiceId);
        // broadcastMessage(bundle);
    }

    @Override
    public synchronized void startForegroundService() {
        start();
    }

    @Override
    public synchronized void stop() {
        if (!sIsRunning) return;

        if (mProvider != null) {
            mProvider.onStop();
        }

        stopForeground(true);
        stopSelf();

        broadcastMessage(MSG_ON_SERVICE_STOPPED);
        sIsRunning = false;
    }

    @Override
    public void startForeground() {
        if (sIsRunning && !mIsInForeground) {
            Config config = getConfig();
            Notification notification = new NotificationHelper.NotificationFactory(this).getNotification(
                    config.getNotificationTitle(),
                    config.getNotificationText(),
                    config.getLargeNotificationIcon(),
                    config.getSmallNotificationIcon(),
                    config.getNotificationIconColor());

            if (mProvider != null) {
                mProvider.onCommand(LocationProvider.CMD_SWITCH_MODE, LocationProvider.FOREGROUND_MODE);
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    super.startForeground(NOTIFICATION_ID, notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                } else {
                    super.startForeground(NOTIFICATION_ID, notification);
                }
                mIsInForeground = true;
            } catch(Exception error) {
                logger.error("Foreground Error: {}", error.getMessage());
            }
        }
    }

    @Override
    public synchronized void stopForeground() {
        if (sIsRunning && mIsInForeground) {
            stopForeground(true);
            if (mProvider != null) {
                mProvider.onCommand(LocationProvider.CMD_SWITCH_MODE, LocationProvider.BACKGROUND_MODE);
            }
            mIsInForeground = false;
        }
    }

    @Override
    public void setting(Setting setting) {
        mSetting = setting;
    }

    @Override
    public synchronized void configure(Config config) {
        if (mConfig == null) {
            mConfig = config;
            return;
        }

        final Config currentConfig = mConfig;
        mConfig = config;

        mPostLocationTask.setConfig(mConfig);

        if (mSetting == null) {
            mSetting = getSetting();
        }

        if(!mSetting.isStarted()){
            sIsRunning = false;
        }

        ThreadUtils.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (sIsRunning) {
                    if (currentConfig.getStartForeground() && !mConfig.getStartForeground()) {
                        stopForeground(true);
                    }

                    if (mConfig.getStartForeground()) {
                        if (!currentConfig.getStartForeground()) {
                            startForeground();
                        } else {
                            Notification notification = new NotificationHelper.NotificationFactory(LocationServiceImpl.this).getNotification(
                                    mConfig.getNotificationTitle(),
                                    mConfig.getNotificationText(),
                                    mConfig.getLargeNotificationIcon(),
                                    mConfig.getSmallNotificationIcon(),
                                    mConfig.getNotificationIconColor());

                            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                            notificationManager.notify(NOTIFICATION_ID, notification);
                            notificationManager.cancel(PERMISSION_NOTIFICATION_ID);
                        }
                    }
                }

                if (currentConfig.getLocationProvider() != mConfig.getLocationProvider()) {
                    boolean shouldStart = mProvider.isStarted();
                    mProvider.onDestroy();
                    LocationProviderFactory spf = new LocationProviderFactory(LocationServiceImpl.this);
                    mProvider = spf.getInstance(mConfig.getLocationProvider());
                    mProvider.setDelegate(LocationServiceImpl.this);
                    mProvider.onCreate();
                    mProvider.onConfigure(mConfig);
                    if (shouldStart) {
                        mProvider.onStart();
                    }
                } else {
                    mProvider.onConfigure(mConfig);
                }
            }
        });
    }

    @Override
    public synchronized void registerHeadlessTask(String taskRunnerClass) {
        mHeadlessTaskRunnerClass = taskRunnerClass;
    }

    @Override
    public synchronized void startHeadlessTask() {
        if (mHeadlessTaskRunnerClass != null) {
            TaskRunnerFactory trf = new TaskRunnerFactory();
            try {
                mHeadlessTaskRunner = trf.getTaskRunner(mHeadlessTaskRunnerClass);
                ((AbstractTaskRunner) mHeadlessTaskRunner).setContext(this);
            } catch (Exception e) {
                logger.error("Headless task start failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public synchronized void stopHeadlessTask() {
        mHeadlessTaskRunner = null;
    }

    @Override
    public synchronized void executeProviderCommand(final int command, final int arg1) {
        if (mProvider == null) return;

        mServiceHandler.post(new Runnable() {
            @Override
            public void run() {
                mProvider.onCommand(command, arg1);
            }
        });
    }

    @Override
    public void onLocation(BackgroundLocation location) {
        logger.debug("New location {}", location.toString());

        location = transformLocation(location);
        
        if (location == null) {
            logger.debug("Skipping location as requested by the locationTransform");
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_LOCATION);
        bundle.putParcelable("payload", location);
        broadcastMessage(bundle);

        runHeadlessTask(new LocationTask(location) {
            @Override
            public void onError(String errorMessage) { logger.error("Location task error: {}", errorMessage); }
            @Override
            public void onResult(String value) { logger.debug("Location task result: {}", value); }
        });

        postLocation(location);
    }

    @Override
    public void onStationary(BackgroundLocation location) {
        logger.debug("New stationary heartbeat ping received natively: {}", location.toString());
        location = transformLocation(location);
        
        if (location == null) {
            logger.debug("Skipping location as requested by the locationTransform");
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_STATIONARY);
        bundle.putParcelable("payload", location);
        broadcastMessage(bundle);

        runHeadlessTask(new StationaryTask(location){
            @Override
            public void onError(String errorMessage) { logger.error("Stationary task error: {}", errorMessage); }
            @Override
            public void onResult(String value) { logger.debug("Stationary task result: {}", value); }
        });

        postLocation(location);
        
        logger.debug("Flushing Proof of Life stationary ping directly to server.");
        scheduleNetworkSync(true);
    }

    @Override
    public void onActivity(BackgroundActivity activity) {
        logger.debug("New activity {}", activity.toString());

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_ACTIVITY);
        bundle.putParcelable("payload", activity);
        broadcastMessage(bundle);

        runHeadlessTask(new ActivityTask(activity){
            @Override
            public void onError(String errorMessage) { logger.error("Activity task error: {}", errorMessage); }
            @Override
            public void onResult(String value) { logger.debug("Activity task result: {}", value); }
        });
    }

    private void postError(PluginException error) {
        mPostLocationTask.add(error);
    }

    @Override
    public void onError(PluginException error) {
        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_ERROR);
        bundle.putBundle("payload", error.toBundle());
        broadcastMessage(bundle);
        postError(error);
        if(error.getCode() == PluginException.PERMISSION_DENIED_ERROR) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(LocationServiceImpl.this, NotificationHelper.ANDROID_PERMISSIONS_CHANNEL_ID);
            builder.setContentTitle("Permission Denied");
            builder.setContentText("Location Permission is denied. Please Allow the location.");
            builder.setSmallIcon(android.R.drawable.ic_dialog_info);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(PERMISSION_NOTIFICATION_ID, builder.build());
        }
    }

    private void broadcastMessage(int msgId) {
        Bundle bundle = new Bundle();
        bundle.putInt("action", msgId);
        broadcastMessage(bundle);
    }

    private void broadcastMessage(Bundle bundle) {
        Intent intent = new Intent(ACTION_BROADCAST);
        intent.putExtras(bundle);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return super.registerReceiver(receiver, filter, null , mServiceHandler, Context.RECEIVER_EXPORTED);
        } else {
           return super.registerReceiver(receiver, filter, null, mServiceHandler);
        }
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        try {
            super.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ex) { }
    }

    public Config getConfig() {
        Config config = mConfig;
        if (config == null) {
            ConfigurationDAO dao = DAOFactory.createConfigurationDAO(this);
            try {
                config = dao.retrieveConfiguration();
            } catch (JSONException e) {
                logger.error("Config exception: {}", e.getMessage());
            }
        }
        if (config == null) { config = Config.getDefault(); }
        mConfig = config;
        return mConfig;
    }

    public Setting getSetting() {
        Setting setting = mSetting;
        if (setting == null) {
            SettingDAO dao = DAOFactory.createSettingDAO(this);
            try {
                setting = dao.retrieveSetting();
            } catch (JSONException e) {
                logger.error("Setting exception: {}", e.getMessage());
            }
        }
        if (setting == null) { setting = Setting.getDefault(); }
        mSetting = setting;
        return mSetting;
    }

    public static void setLocationProviderFactory(LocationProviderFactory factory) {
        sLocationProviderFactory = factory;
    }

    private void runHeadlessTask(Task task) {
        if (mHeadlessTaskRunner == null) return;
        logger.debug("Running headless task: {}", task);
        mHeadlessTaskRunner.runTask(task);
    }

    public class LocalBinder extends Binder {
        public LocationServiceImpl getService() {
            return LocationServiceImpl.this;
        }
    }

    private BackgroundLocation transformLocation(BackgroundLocation location) {
        if (sLocationTransform != null) {
            return sLocationTransform.transformLocationBeforeCommit(this, location);
        }
        return location;
    }

    private void postLocation(BackgroundLocation location) {
        mPostLocationTask.add(location);
    }

    public void handleRequestedAbortUpdates() {
        broadcastMessage(MSG_ON_ABORT_REQUESTED);
    }

    public void handleHttpAuthorizationUpdates() {
        broadcastMessage(MSG_ON_HTTP_AUTHORIZATION);
    }

    private BroadcastReceiver connectivityChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean hasConnectivity = isNetworkAvailable();
            mPostLocationTask.setHasConnectivity(hasConnectivity);
            logger.info("Network condition changed has connectivity: {}", hasConnectivity);
        }
    };

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    public long getServiceId() { return mServiceId; }
    public boolean isBound() {
        LocationServiceInfo info = new LocationServiceInfoImpl(this);
        return info.isBound();
    }
    public static boolean isRunning() { return sIsRunning; }
    public static void setLocationTransform(@Nullable LocationTransform transform) { sLocationTransform = transform; }
    public static @Nullable LocationTransform getLocationTransform() { return sLocationTransform; }

    private void scheduleNetworkSync(boolean forceImmediate) {
        logger.debug("Scheduling WorkManager to sync locations to the server.");

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

        WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
                "LocationSyncJob",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                syncRequest
        );
    }

    private boolean isLegacyEngineActive() {
        if (mProvider == null) return true; 
        
        return !(mProvider instanceof FusedDistanceFilterLocationProvider);
    }
}
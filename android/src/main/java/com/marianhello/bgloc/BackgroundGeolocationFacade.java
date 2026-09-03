package com.marianhello.bgloc;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import com.github.jparkie.promise.Promise;
import com.intentfilter.androidpermissions.PermissionManager;
import com.marianhello.bgloc.data.BackgroundActivity;
import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.LocationTransform;
import com.marianhello.bgloc.provider.LocationProvider;
import com.marianhello.bgloc.service.LocationService;
import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.bgloc.service.LocationServiceProxy;
import com.marianhello.bgloc.sync.LocationSyncWorker;
import com.marianhello.bgloc.sync.NotificationHelper;
import com.marianhello.bgloc.sync.WorkManagerHelper;
import com.marianhello.logging.DBLogReader;
import com.marianhello.logging.LogEntry;
import com.marianhello.logging.LoggerManager;
import com.marianhello.logging.UncaughtExceptionLogger;
import com.marianhello.utils.RealTimeHelper;

import org.json.JSONException;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class BackgroundGeolocationFacade {

    public static final int SERVICE_STARTED = 1;
    public static final int SERVICE_STOPPED = 0;
    public static final int AUTHORIZATION_AUTHORIZED = 1;
    public static final int AUTHORIZATION_DENIED = 0;

    private static String[] buildInitialPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }

    private static String[] buildAllPermissions() {
        List<String> perms = new ArrayList<>(Arrays.asList(buildInitialPermissions()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
        return perms.toArray(new String[0]);
    }

    public static final String[] INITIALPERMISSIONS = buildInitialPermissions();
    public static final String[] BACKGROUNDLOCATIONPERMISSION = new String[]{
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
    };
    public static final String[] PERMISSIONS = buildAllPermissions();

    private boolean mServiceBroadcastReceiverRegistered = false;
    private boolean mLocationModeChangeReceiverRegistered = false;
    private boolean mIsPaused = false;

    private Config mConfig;
    private Setting mSetting;
    private final Context mContext;
    private final PluginDelegate mDelegate;
    private final LocationService mService;
    private BackgroundLocation mStationaryLocation;
    private final org.slf4j.Logger logger;

    public BackgroundGeolocationFacade(Context context, PluginDelegate delegate) {
        mContext = context;
        mDelegate = delegate;
        mService = new LocationServiceProxy(context);

        Context appContext = context.getApplicationContext();
        UncaughtExceptionLogger.register(appContext);
        logger = LoggerManager.getLogger(BackgroundGeolocationFacade.class);
        LoggerManager.enableDBLogging();

        logger.info("Initializing plugin facade layer.");
        NotificationHelper.registerAllChannels(appContext);
        RealTimeHelper.initialize(appContext);
    }

    private final BroadcastReceiver locationModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logger.debug("System location authorization changed.");
            if (mDelegate != null) {
                mDelegate.onAuthorizationChanged(getAuthorizationStatus());
            }
        }
    };

    private final BroadcastReceiver serviceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent.getExtras();
            if (bundle == null) return;
            
            int action = bundle.getInt("action");
            bundle.setClassLoader(LocationServiceImpl.class.getClassLoader());

            switch (action) {
                case LocationServiceImpl.MSG_ON_LOCATION: {
                    BackgroundLocation location = bundle.getParcelable("payload");
                    if (mDelegate != null) mDelegate.onLocationChanged(location);
                    break;
                }
                case LocationServiceImpl.MSG_ON_STATIONARY: {
                    BackgroundLocation location = bundle.getParcelable("payload");
                    mStationaryLocation = location;
                    if (mDelegate != null) mDelegate.onStationaryChanged(location);
                    break;
                }
                case LocationServiceImpl.MSG_ON_ACTIVITY: {
                    BackgroundActivity activity = bundle.getParcelable("payload");
                    if (mDelegate != null) mDelegate.onActivityChanged(activity);
                    break;
                }
                case LocationServiceImpl.MSG_ON_ERROR: {
                    Bundle errorBundle = bundle.getBundle("payload");
                    if (errorBundle != null && mDelegate != null) {
                        mDelegate.onError(new PluginException(errorBundle.getString("message"), errorBundle.getInt("code")));
                    }
                    break;
                }
                case LocationServiceImpl.MSG_ON_SERVICE_STARTED: {
                    if (mDelegate != null) mDelegate.onServiceStatusChanged(SERVICE_STARTED);
                    break;
                }
                case LocationServiceImpl.MSG_ON_SERVICE_STOPPED: {
                    if (mDelegate != null) mDelegate.onServiceStatusChanged(SERVICE_STOPPED);
                    break;
                }
                case LocationServiceImpl.MSG_ON_ABORT_REQUESTED: {
                    if (mDelegate != null) {
                        mDelegate.onAbortRequested();
                    } else {
                        stop();
                    }
                    break;
                }
                case LocationServiceImpl.MSG_ON_HTTP_AUTHORIZATION: {
                    if (mDelegate != null) mDelegate.onHttpAuthorization();
                    break;
                }
            }
        }
    };

    private synchronized void registerLocationModeChangeReceiver() {
        if (mLocationModeChangeReceiverRegistered) return;

        IntentFilter filter = new IntentFilter(android.location.LocationManager.MODE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplicationContext().registerReceiver(locationModeChangeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            getApplicationContext().registerReceiver(locationModeChangeReceiver, filter);
        }
        mLocationModeChangeReceiverRegistered = true;
    }

    private synchronized void unregisterLocationModeChangeReceiver() {
        if (!mLocationModeChangeReceiverRegistered) return;
        try {
            getApplicationContext().unregisterReceiver(locationModeChangeReceiver);
        } catch (Exception ignored) {}
        mLocationModeChangeReceiverRegistered = false;
    }

    private synchronized void registerServiceBroadcast() {
        if (mServiceBroadcastReceiverRegistered) return;
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(
                serviceBroadcastReceiver,
                new IntentFilter(LocationServiceImpl.ACTION_BROADCAST)
        );
        mServiceBroadcastReceiverRegistered = true;
    }

    private synchronized void unregisterServiceBroadcast() {
        if (!mServiceBroadcastReceiverRegistered) return;
        try {
            LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(serviceBroadcastReceiver);
        } catch (Exception ignored) {}
        mServiceBroadcastReceiverRegistered = false;
    }

    public void start() {
        logger.debug("Requesting engine initialization sequence.");
        final PermissionManager permissionManager = PermissionManager.getInstance(getContext());

        permissionManager.checkPermissions(Arrays.asList(INITIALPERMISSIONS), new PermissionManager.PermissionRequestListener() {
            @Override
            public void onPermissionGranted() {
                logger.info("Foreground tracking assets authorized.");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (hasPermissions(getApplicationContext(), BACKGROUNDLOCATIONPERMISSION)) {
                        executeTrackingServiceStart();
                    } else {
                        logger.info("Forwarding split-permission validation sequence for background location.");
                        permissionManager.checkPermissions(Arrays.asList(BACKGROUNDLOCATIONPERMISSION), new PermissionManager.PermissionRequestListener() {
                            @Override
                            public void onPermissionGranted() {
                                executeTrackingServiceStart();
                            }

                            @Override
                            public void onPermissionDenied() {
                                logger.warn("User explicitly declined 'Allow all the time' background tracking permission.");
                                if (mDelegate != null) mDelegate.onAuthorizationChanged(AUTHORIZATION_DENIED);
                            }
                        });
                    }
                } else {
                    executeTrackingServiceStart();
                }
            }

            @Override
            public void onPermissionDenied() {
                logger.warn("Core application location privileges denied.");
                if (mDelegate != null) mDelegate.onAuthorizationChanged(AUTHORIZATION_DENIED);
            }
        });
    }

    private void executeTrackingServiceStart() {
        registerLocationModeChangeReceiver();
        registerServiceBroadcast();
        startBackgroundService();
    }

    public void stop() {
        logger.debug("Halting tracking pipeline.");
        unregisterLocationModeChangeReceiver();
        stopBackgroundService();
    }

    public void pause() {
        mIsPaused = true;
        mService.startForeground();
    }

    public void resume() {
        mIsPaused = false;
        mService.stopHeadlessTask();
        if (!getConfig().getStartForeground()) {
            mService.stopForeground();
        }
    }

    public void destroy() {
        logger.info("Tearing down Facade interface lifecycle.");
        unregisterLocationModeChangeReceiver();
        unregisterServiceBroadcast();

        if (getConfig().getStopOnTerminate() || !getSetting().isStarted()) {
            stopBackgroundService();
        } else {
            mService.startHeadlessTask();
        }
    }

    public Collection<BackgroundLocation> getLocations() {
        return DAOFactory.createLocationDAO(getApplicationContext()).getAllLocations();
    }

    public Collection<BackgroundLocation> getValidLocations() {
        return DAOFactory.createLocationDAO(getApplicationContext()).getValidLocations();
    }

    public BackgroundLocation getStationaryLocation() {
        return mStationaryLocation;
    }

    public void deleteLocation(Long locationId) {
        DAOFactory.createLocationDAO(getApplicationContext()).deleteLocationById(locationId);
    }

    public void deleteAllLocations() {
        DAOFactory.createLocationDAO(getApplicationContext()).deleteAllLocations();
    }

    public void deleteAllLocationsPermanent(long millisBeforeTimeStamp) {
        DAOFactory.createLocationDAO(getApplicationContext()).deleteAllLocationsPermanent(millisBeforeTimeStamp);
    }

    public BackgroundLocation getCurrentLocation(int timeout, long maximumAge, boolean enableHighAccuracy) throws PluginException {
        logger.info("Getting current location with timeout:{} maximumAge:{} enableHighAccuracy:{}", timeout, maximumAge, enableHighAccuracy);

        LocationManager locationManager = LocationManager.getInstance(getContext());
        Promise<Location> promise = locationManager.getCurrentLocation(timeout, maximumAge, enableHighAccuracy);
        try {
            promise.await();
            Location location = promise.get();
            if (location != null) {
                return BackgroundLocation.fromLocation(location);
            }

            Throwable error = promise.getError();
            if (error == null) {
                throw new PluginException("Location not available", 2);
            }
            if (error instanceof LocationManager.PermissionDeniedException) {
                logger.warn("Getting current location failed due to missing permissions");
                throw new PluginException("Permission denied", 1);
            }
            if (error instanceof TimeoutException) {
                throw new PluginException("Location request timed out", 3);
            }

            throw new PluginException(error.getMessage(), 2);
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for location fix", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for location fix", e);
        }
    }

    public void switchMode(final int mode) {
        mService.executeProviderCommand(LocationProvider.CMD_SWITCH_MODE, mode);
    }

    public void sendCommand(final int commandId) {
        mService.executeProviderCommand(commandId, 0);
    }

    public synchronized void configure(Config config) throws PluginException {
        try {
            Config newConfig = Config.merge(getStoredConfig(), config);
            persistConfiguration(newConfig);
            mConfig = newConfig;
            mService.configure(newConfig);
        } catch (Exception e) {
            throw new PluginException("Configuration synchronization failure", e, PluginException.CONFIGURE_ERROR);
        }
    }

    public synchronized void setting(Setting setting) throws PluginException {
        try {
            Setting newSetting = Setting.merge(getStoredSetting(), setting);
            persistSetting(newSetting);
            mSetting = newSetting;
            mService.setting(newSetting);
        } catch (Exception e) {
            throw new PluginException("Setting persistence failure", e, PluginException.CONFIGURE_ERROR);
        }
    }

    public synchronized Config getConfig() {
        if (mConfig != null) return mConfig;
        try {
            mConfig = getStoredConfig();
        } catch (PluginException e) {
            mConfig = Config.getDefault();
        }
        return mConfig;
    }

    public synchronized Setting getSetting() {
        if (mSetting != null) return mSetting;
        try {
            mSetting = getStoredSetting();
        } catch (PluginException e) {
            mSetting = Setting.getDefault();
        }
        return mSetting;
    }

    public synchronized Config getStoredConfig() throws PluginException {
        try {
            Config config = DAOFactory.createConfigurationDAO(getApplicationContext()).retrieveConfiguration();
            return (config != null) ? config : Config.getDefault();
        } catch (JSONException e) {
            throw new PluginException("Config payload extraction error", e, PluginException.JSON_ERROR);
        }
    }

    public synchronized Setting getStoredSetting() throws PluginException {
        try {
            Setting setting = DAOFactory.createSettingDAO(getApplicationContext()).retrieveSetting();
            return (setting != null) ? setting : Setting.getDefault();
        } catch (JSONException e) {
            throw new PluginException("Setting payload extraction error", e, PluginException.JSON_ERROR);
        }
    }

    public Collection<LogEntry> getLogEntries(int limit) {
        return new DBLogReader(getApplicationContext()).getEntries(limit, 0, Level.DEBUG);
    }

    public Collection<LogEntry> getLogEntries(int limit, int offset, String minLevel) {
        return new DBLogReader(getApplicationContext()).getEntries(limit, offset, Level.valueOf(minLevel));
    }

    public void sync() {
        forceSync();
    }

    public void forceSync() {
        logger.debug("Sync locations requested through unified facade bridge.");

        if (LocationServiceImpl.isRunning()) {
            mService.sync();
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        Data inputData = new Data.Builder()
                .putBoolean(LocationSyncWorker.INPUT_FORCE_SYNC, true)
                .putInt(LocationSyncWorker.INPUT_DYNAMIC_SYNC_THRESHOLD, 0)
                .build();

        OneTimeWorkRequest syncRequest = new OneTimeWorkRequest.Builder(LocationSyncWorker.class)
                .setConstraints(constraints)
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();

        try {
            WorkManagerHelper.getWorkManager(getApplicationContext()).enqueueUniqueWork(
                    "LocationSyncJob",
                    ExistingWorkPolicy.REPLACE,
                    syncRequest
            );
        } catch (Exception e) {
            logger.error("Failed to enqueue forceSync through WorkManagerHelper.", e);
        }
    }

    public int getAuthorizationStatus() {
        return hasPermissions() ? AUTHORIZATION_AUTHORIZED : AUTHORIZATION_DENIED;
    }

    public boolean hasPermissions() {
        return hasPermissions(getApplicationContext(), PERMISSIONS);
    }

    public boolean locationServicesEnabled() throws PluginException {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                int locationMode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE);
                return locationMode != Settings.Secure.LOCATION_MODE_OFF;
            } catch (SettingNotFoundException e) {
                throw new PluginException("Location hardware provider verification failed.", e, PluginException.SETTINGS_ERROR);
            }
        } else {
            String locationProviders = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
            return !TextUtils.isEmpty(locationProviders);
        }
    }

    public void registerHeadlessTask(final String taskRunnerClass) {
        mService.registerHeadlessTask(taskRunnerClass);
    }

    private void startBackgroundService() {
        if (mIsPaused) {
            mService.startForegroundService();
        } else {
            mService.start();
        }
    }

    private void stopBackgroundService() {
        mService.stop();
    }

    public boolean isRunning() {
        return ((LocationServiceProxy) mService).isRunning();
    }

    private void persistConfiguration(Config config) {
        DAOFactory.createConfigurationDAO(getApplicationContext()).persistConfiguration(config);
    }

    private void persistSetting(Setting setting) {
        DAOFactory.createSettingDAO(getApplicationContext()).persistSetting(setting);
    }

    private Context getContext() {
        return mContext;
    }

    private Context getApplicationContext() {
        return mContext.getApplicationContext();
    }

    public static void showAppSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }

    public static void showLocationSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }

    public static boolean hasPermissions(Context context, String[] permissions) {
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static void setLocationTransform(LocationTransform transform) {
        LocationServiceImpl.setLocationTransform(transform);
    }

    public static LocationTransform getLocationTransform() {
        return LocationServiceImpl.getLocationTransform();
    }
}
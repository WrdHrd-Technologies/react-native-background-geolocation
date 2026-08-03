package com.marianhello.bgloc.sync;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.concurrent.futures.CallbackToFutureAdapter; // Required dependency
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.ForegroundInfo; // Required
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.common.util.concurrent.ListenableFuture; // Required
import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.HttpPostService;
import com.marianhello.bgloc.Setting;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.SettingDAO;
import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.bgloc.service.LocationServiceIntentBuilder;
import com.marianhello.logging.LoggerManager;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class LocationSyncWorker extends Worker implements HttpPostService.UploadingProgressListener {
    private static final String TAG = "LocationSyncWorker";
    private static final int NOTIFICATION_ID = 666;

    private final org.slf4j.Logger logger;
    private final ConfigurationDAO configDAO;
    private final BatchManager batchManager;
    private final NotificationManager notificationManager;
    private final Handler mainThreadHandler;

    private boolean notificationsEnabled = true;
    private int lastReportedProgress = -1;

    public LocationSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.logger = LoggerManager.getLogger(LocationSyncWorker.class);
        this.configDAO = DAOFactory.createConfigurationDAO(context);
        this.batchManager = new BatchManager(context);
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.mainThreadHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Required by WorkManager to prevent crashes when executing Expedited WorkRequests.
     */
    @NonNull
    @Override
    public ListenableFuture<ForegroundInfo> getForegroundInfoAsync() {
        return CallbackToFutureAdapter.getFuture(completer -> {
            Context context = getApplicationContext();
            
            // Explicitly ensure the Notification Channel exists for Android O+ 
            // before delivering ForegroundInfo to the system.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        NotificationHelper.SYNC_CHANNEL_ID,
                        "Location Synchronization",
                        NotificationManager.IMPORTANCE_LOW
                );
                if (notificationManager != null) {
                    notificationManager.createNotificationChannel(channel);
                }
            }

            // Fallback notification configuration if build parameters are not initialized yet
            Notification notification = new NotificationCompat.Builder(context, NotificationHelper.SYNC_CHANNEL_ID)
                    .setContentTitle("Syncing locations")
                    .setContentText("Sync in progress")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();

            completer.set(new ForegroundInfo(NOTIFICATION_ID, notification));
            return "LocationSyncWorkerForegroundInfo";
        });
    }

    @NonNull
    @Override
    public Result doWork() {
        logger.warn("LocationSyncWorker START");
        
        if (isStopped()) {
            logger.warn("WorkManager canceled execution context before thread initialization.");
            return Result.failure();
        }

        final Context context = getApplicationContext();

        boolean shouldResurrectOnUpgrade = getInputData().getBoolean("resurrect_service", false);
        boolean shouldResurrectOnBoot = getInputData().getBoolean("resurrect_on_boot", false);

        if (shouldResurrectOnUpgrade || shouldResurrectOnBoot) {
            logger.info("Worker background thread: Boot/Upgrade event captured. Validating tracking configuration parameters in SQLite.");
            try {
                SettingDAO workerSettingDao = DAOFactory.createSettingDAO(context);
                Setting trackingSetting = workerSettingDao.retrieveSetting();

                boolean isTrackingActive = trackingSetting != null && trackingSetting.isStarted();
                boolean isBootAllowed = true;

                if (shouldResurrectOnBoot) {
                    ConfigurationDAO workerConfigDao = DAOFactory.createConfigurationDAO(context);
                    Config trackingConfig = workerConfigDao.retrieveConfiguration();
                    isBootAllowed = (trackingConfig != null && trackingConfig.getStartOnBoot());
                }

                if (isTrackingActive && isBootAllowed) {
                    logger.info("Worker: Conditions verified. Reviving core tracking service context safely.");
                    
                    Intent serviceIntent = new Intent(context, LocationServiceImpl.class);
                    serviceIntent.putExtra("cmd", new LocationServiceIntentBuilder.Command(0).toBundle());
                    serviceIntent.addFlags(Intent.FLAG_FROM_BACKGROUND);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    logger.info("Worker: Foreground resurrection intent dispatched successfully.");
                } else {
                    logger.info("Worker: Tracking status or startOnBoot preference is disabled. Skipping service startup.");
                }
            } catch (Exception e) {
                logger.error("Worker background thread failed processing system restoration gate logic.", e);
            }
        }

        Config config;
        try {
            config = configDAO.retrieveConfiguration();
        } catch (Exception e) {
            logger.error("Failed parsing configurations inside the worker execution frame: {}", e.getMessage());
            return Result.failure();
        }

        if (config == null || !config.hasValidSyncUrl()) {
            logger.warn("Target API endpoint URL is blank or unconfigured. Halting processing engine.");
            return Result.failure();
        }

        final long batchStartMillis = System.currentTimeMillis();
        final boolean isForced = getInputData().getBoolean("force_sync", false);

        this.notificationsEnabled = isForced && (!config.hasNotificationsEnabled() || config.getNotificationsEnabled());
        
        int syncThreshold = (isForced || getRunAttemptCount() > 0) ? 0 : config.getSyncThreshold();
        logger.debug("WorkManager dispatch initialized. Attempt count: {}, Assigned Batch ID: {}", getRunAttemptCount(), batchStartMillis);

        File file = null;
        try {
            if (isStopped()) return Result.retry();

            file = batchManager.createBatch(batchStartMillis, syncThreshold, config.getTemplate());
            
            if (file == null) {
                logger.info("Synchronization condition aborted: Allocation empty or threshold unsatisfied.");
                return Result.success();
            }

            if (isStopped()) {
                cleanUpBatchFile(file);
                return Result.retry();
            }

            logger.info("Streaming tracking data packet payload outbound targeting timestamp: {}", batchStartMillis);

            Map<String, String> headers = new HashMap<>();
            if (config.getHttpHeaders() != null) {
                for (Map.Entry<?, ?> entry : config.getHttpHeaders().entrySet()) {
                    if (entry.getKey() != null) {
                        String key = String.valueOf(entry.getKey());
                        String value = (entry.getValue() != null) ? String.valueOf(entry.getValue()) : "";
                        headers.put(key, value);
                    }
                }
            }
            headers.put("x-batch-id", String.valueOf(batchStartMillis));

            boolean success = uploadLocations(file, config.getSyncUrl(), headers);

            if (isStopped()) {
                logger.warn("Worker intercepted a system stop request post-upload transaction. Relinquishing batch changes.");
                cleanUpBatchFile(file);
                return Result.retry();
            }

            if (success) {
                logger.info("Location synchronizer batch transaction finalized cleanly.");
                batchManager.setBatchCompleted(batchStartMillis);
                return Result.success();
            } else {
                logger.warn("Network transmission rejected by upstream API server. Escalating to backoff retry queue.");
                return Result.retry();
            }

        } catch (IOException e) {
            logger.error("IO barrier breakdown encountered while preparing offline caching configurations.", e);
            return Result.retry();
        } finally {
            cleanUpBatchFile(file);
        }
    }

    private boolean uploadLocations(@NonNull File file, @NonNull String url, @NonNull Map<String, String> httpHeaders) {
        if (isStopped()) return false;

        final NotificationCompat.Builder builder = createBaseNotificationBuilder();
        if (builder != null && hasNotificationPermission()) {
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        }

        logger.info("Syncing at " + url);

        try {
            HashMap<String, String> legacyMapWrapper = new HashMap<>(httpHeaders);
            int responseCode = HttpPostService.postJSONFile(url, file, legacyMapWrapper, this);
            
            if (isStopped()) return false;

            boolean isStatusOkay = responseCode >= 200 && responseCode < 300;

            if (responseCode == 285) {
                logger.warn("Downstream telemetry command termination [HTTP 285] intercept executed.");
                sendActionBroadcast(LocationServiceImpl.MSG_ON_ABORT_REQUESTED);
            }

            if (responseCode == 401) {
                logger.warn("Authentication credentials rejected [HTTP 401]. Dispatched renewal request.");
                sendActionBroadcast(LocationServiceImpl.MSG_ON_HTTP_AUTHORIZATION);
            }

            if (builder != null) {
                builder.setContentText(isStatusOkay ? "Sync completed" : "Sync failed due to server error");
            }

            return isStatusOkay;
            
        } catch (IOException e) {
            logger.warn("Exception processed during raw network stream pipe writing operations: {}", e.getMessage());
            if (builder != null) {
                builder.setContentText("Sync failed: " + e.getMessage());
            }
            return false;
        } finally {
            if (builder != null) {
                mainThreadHandler.post(() -> {
                    try {
                        builder.setOngoing(false);
                        builder.setProgress(0, 0, false);
                        builder.setAutoCancel(true);
                        if (hasNotificationPermission()) {
                            notificationManager.notify(NOTIFICATION_ID, builder.build());
                        }
                        
                        mainThreadHandler.postDelayed(() -> {
                            try {
                                notificationManager.cancel(NOTIFICATION_ID);
                            } catch (Exception ignored) {}
                        }, 5000);
                        
                    } catch (Exception e) {
                        Log.w(TAG, "Error finalizing status bar notification presentation states.", e);
                    }
                });
            }
        }
    }

    @Override
    public void onProgress(final int progress) {
        if (isStopped() || !notificationsEnabled || !hasNotificationPermission()) return;

        if (progress == 100 || progress >= lastReportedProgress + 10) {
            this.lastReportedProgress = progress;
            
            mainThreadHandler.post(() -> {
                try {
                    if (isStopped()) return; 
                    NotificationCompat.Builder updateBuilder = createBaseNotificationBuilder();
                    if (updateBuilder != null) {
                        updateBuilder.setOngoing(true)
                                .setOnlyAlertOnce(true)
                                .setProgress(100, progress, false);
                        
                        notificationManager.notify(NOTIFICATION_ID, updateBuilder.build());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed pushing asynchronous thread progress metrics to status bar.", e);
                }
            });
        }
    }

    private void cleanUpBatchFile(@Nullable File file) {
        if (file != null && file.exists()) {
            try {
                if (file.delete()) {
                    logger.info("Transient batch cache workspace file purged cleanly from persistent storage.");
                } else {
                    Log.w(TAG, "File engine failed reclaiming internal workspace resources: " + file.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error executing file cleanup operations inside isolation wrapper.", e);
            }
        }
    }

    @Override
    public void onStopped() {
        super.onStopped();
        logger.warn("⚠️ WorkManager forcefully triggered native onStopped() callback sequence. Shutting down worker handles.");
        try {
            notificationManager.cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {}
    }

    @Nullable
    private NotificationCompat.Builder createBaseNotificationBuilder() {
        if (!notificationsEnabled) return null;
        return new NotificationCompat.Builder(getApplicationContext(), NotificationHelper.SYNC_CHANNEL_ID)
                .setContentTitle("Syncing locations")
                .setContentText("Sync in progress")
                .setSmallIcon(android.R.drawable.ic_dialog_info);
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(getApplicationContext(), 
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void sendActionBroadcast(int actionId) {
        Bundle bundle = new Bundle();
        bundle.putInt("action", actionId);
        Intent intent = new Intent(LocationServiceImpl.ACTION_BROADCAST);
        intent.putExtras(bundle);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }
}
package com.marianhello.bgloc.sync;

import android.Manifest;
import android.app.Notification;
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
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

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
    private final BatchManager batchManager;
    private final NotificationManager notificationManager;
    private final Handler mainThreadHandler;
    private final NotificationHelper.NotificationFactory notificationFactory;

    private boolean notificationsEnabled = true;
    private int lastReportedProgress = -1;

    public LocationSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.logger = LoggerManager.getLogger(LocationSyncWorker.class);
        this.batchManager = new BatchManager(context);
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.mainThreadHandler = new Handler(Looper.getMainLooper());
        this.notificationFactory = new NotificationHelper.NotificationFactory(context);
    }

    @NonNull
    @Override
    public com.google.common.util.concurrent.ListenableFuture<androidx.work.ForegroundInfo> getForegroundInfoAsync() {
        Context context = getApplicationContext();

        NotificationHelper.registerSyncChannel(context);
        Notification notification = notificationFactory.getSyncNotification("Syncing locations", "Sync in progress");

        final androidx.work.ForegroundInfo foregroundInfo = new androidx.work.ForegroundInfo(NOTIFICATION_ID, notification);

        return new com.google.common.util.concurrent.ListenableFuture<androidx.work.ForegroundInfo>() {
            @Override
            public void addListener(Runnable listener, java.util.concurrent.Executor executor) {
                if (listener != null && executor != null) {
                    executor.execute(listener);
                }
            }

            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isDone() { return true; }
            @Override public androidx.work.ForegroundInfo get() { return foregroundInfo; }
            @Override public androidx.work.ForegroundInfo get(long timeout, @NonNull java.util.concurrent.TimeUnit unit) { return foregroundInfo; }
        };
    }

    @NonNull
    @Override
    public Result doWork() {
        if (isStopped()) {
            logger.warn("WorkManager canceled execution before initialization.");
            return Result.failure();
        }

        final Context context = getApplicationContext();

        SettingDAO settingDAO = DAOFactory.createSettingDAO(context);
        Setting setting = null;
        try {
            setting = settingDAO.retrieveSetting();
        } catch (Exception e) {
            logger.error("Failed retrieving settings in worker", e);
        }

        if (setting == null || !setting.isStarted()) {
            logger.info("LocationSyncWorker: Tracking is toggled OFF. Exiting worker cleanly.");
            return Result.success();
        }

        boolean shouldResurrectOnUpgrade = getInputData().getBoolean("resurrect_service", false);
        boolean shouldResurrectOnBoot = getInputData().getBoolean("resurrect_on_boot", false);

        if (shouldResurrectOnUpgrade || shouldResurrectOnBoot) {
            handleServiceResurrection(context, shouldResurrectOnBoot);
        }


        ConfigurationDAO configDAO = DAOFactory.createConfigurationDAO(context);
        Config config;
        try {
            config = configDAO.retrieveConfiguration();
        } catch (Exception e) {
            logger.error("Failed parsing configuration: {}", e.getMessage());
            return Result.failure();
        }

        if (config == null || !config.hasValidSyncUrl()) {
            logger.warn("Target API endpoint URL is blank. Halting worker.");
            return Result.failure();
        }

        final long batchStartMillis = System.currentTimeMillis();
        final boolean isForced = getInputData().getBoolean("force_sync", false);

        this.notificationsEnabled = isForced && (!config.hasNotificationsEnabled() || config.getNotificationsEnabled());
        int syncThreshold = (isForced || getRunAttemptCount() > 0) ? 0 : config.getSyncThreshold();

        File file = null;
        try {
            if (isStopped()) return Result.retry();

            file = batchManager.createBatch(batchStartMillis, syncThreshold, config.getTemplate());

            if (file == null) {
                logger.info("Sync condition aborted: Allocation empty or threshold unsatisfied.");
                return Result.success();
            }

            if (isStopped()) {
                cleanUpBatchFile(file);
                return Result.retry();
            }

            Map<String, String> headers = buildHeaders(config, batchStartMillis);
            boolean success = uploadLocations(file, config.getSyncUrl(), headers);

            if (isStopped()) {
                cleanUpBatchFile(file);
                return Result.retry();
            }

            if (success) {
                logger.info("Location synchronizer batch transaction finalized cleanly.");
                batchManager.setBatchCompleted(batchStartMillis);
                return Result.success();
            } else {
                logger.warn("Network transmission rejected. Escalating to backoff retry queue.");
                return Result.retry();
            }

        } catch (IOException e) {
            logger.error("IO error while preparing offline batch configurations: {}", e.getMessage());
            return Result.retry();
        } finally {
            cleanUpBatchFile(file);
        }
    }

    private void handleServiceResurrection(Context context, boolean isBoot) {
        try {
            ConfigurationDAO workerConfigDao = DAOFactory.createConfigurationDAO(context);
            Config trackingConfig = workerConfigDao.retrieveConfiguration();
            boolean isAllowed = !isBoot || (trackingConfig != null && trackingConfig.getStartOnBoot());

            if (isAllowed) {
                Intent serviceIntent = new Intent(context, LocationServiceImpl.class);
                serviceIntent.putExtra("cmd", new LocationServiceIntentBuilder.Command(0).toBundle());
                serviceIntent.addFlags(Intent.FLAG_FROM_BACKGROUND);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                logger.info("Worker: Foreground resurrection intent dispatched successfully.");
            }
        } catch (Exception e) {
            logger.error("Worker failed processing restoration gate logic.", e);
        }
    }

    private Map<String, String> buildHeaders(Config config, long batchStartMillis) {
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
        return headers;
    }

    private boolean uploadLocations(@NonNull File file, @NonNull String url, @NonNull Map<String, String> httpHeaders) {
        if (isStopped()) return false;

        if (notificationsEnabled && hasNotificationPermission()) {
            NotificationHelper.registerSyncChannel(getApplicationContext());
            Notification activeNotification = notificationFactory.getSyncProgressNotification("Syncing locations", "Sync in progress", 0);
            notificationManager.notify(NOTIFICATION_ID, activeNotification);
        }

        boolean isStatusOkay = false;
        String completionText = "Sync failed due to server error";

        try {
            HashMap<String, String> legacyMapWrapper = new HashMap<>(httpHeaders);
            int responseCode = HttpPostService.postJSONFile(url, file, legacyMapWrapper, this);

            if (isStopped()) return false;

            isStatusOkay = responseCode >= 200 && responseCode < 300;
            completionText = isStatusOkay ? "Sync completed" : "Sync failed due to server error";

            if (responseCode == 285) {
                sendActionBroadcast(LocationServiceImpl.MSG_ON_ABORT_REQUESTED);
            } else if (responseCode == 401) {
                sendActionBroadcast(LocationServiceImpl.MSG_ON_HTTP_AUTHORIZATION);
            }

            return isStatusOkay;

        } catch (IOException e) {
            completionText = "Sync failed: " + e.getMessage();
            return false;
        } finally {
            if (notificationsEnabled) {
                final String finalText = completionText;
                mainThreadHandler.post(() -> {
                    try {
                        if (hasNotificationPermission()) {
                            Notification completedNotification = notificationFactory.getSyncCompletedNotification("Syncing locations", finalText);
                            notificationManager.notify(NOTIFICATION_ID, completedNotification);
                        }

                        mainThreadHandler.postDelayed(() -> {
                            try {
                                notificationManager.cancel(NOTIFICATION_ID);
                            } catch (Exception ignored) {}
                        }, 5000);

                    } catch (Exception e) {
                        Log.w(TAG, "Error finalizing status bar notification state.", e);
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
                    Notification progressNotification = notificationFactory.getSyncProgressNotification("Syncing locations", "Sync in progress", progress);
                    notificationManager.notify(NOTIFICATION_ID, progressNotification);
                } catch (Exception e) {
                    Log.e(TAG, "Failed pushing progress metrics to status bar.", e);
                }
            });
        }
    }

    private void cleanUpBatchFile(@Nullable File file) {
        if (file != null && file.exists()) {
            try {
                file.delete();
            } catch (Exception e) {
                Log.e(TAG, "Error executing file cleanup operations.", e);
            }
        }
    }

    @Override
    public void onStopped() {
        super.onStopped();
        try {
            notificationManager.cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {}
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
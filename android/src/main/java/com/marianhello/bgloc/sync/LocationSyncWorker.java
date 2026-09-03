package com.marianhello.bgloc.sync;

import android.Manifest;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.HttpPostService;
import com.marianhello.bgloc.Setting;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.SettingDAO;
import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.bgloc.service.LocationServiceIntentBuilder;
import com.marianhello.logging.LoggerManager;
import com.marianhello.utils.RealTimeHelper;

import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public final class LocationSyncWorker extends Worker implements HttpPostService.UploadingProgressListener {

    private static final String TAG = "LocationSyncWorker";
    private static final int NOTIFICATION_ID = 666;
    private static final int MAX_LOCATIONS_PER_CHUNK = 50;

    public static final String INPUT_RESURRECT_SERVICE = "resurrect_service";
    public static final String INPUT_RESURRECT_ON_BOOT = "resurrect_on_boot";
    public static final String INPUT_FORCE_SYNC = "force_sync";
    public static final String INPUT_DYNAMIC_SYNC_THRESHOLD = "dynamic_sync_threshold";

    private final Logger logger;
    private final BatchManager batchManager;
    private final NotificationManager notificationManager;
    private final Handler mainThreadHandler;
    private final NotificationHelper.NotificationFactory notificationFactory;

    private boolean notificationsEnabled = true;
    private int lastReportedProgress = -1;

    public LocationSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);

        Context appContext = context.getApplicationContext();
        this.logger = LoggerManager.getLogger(LocationSyncWorker.class);
        this.batchManager = new BatchManager(appContext);
        this.notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        this.mainThreadHandler = new Handler(Looper.getMainLooper());
        this.notificationFactory = new NotificationHelper.NotificationFactory(appContext);
    }

    @NonNull
    @Override
    public ListenableFuture<ForegroundInfo> getForegroundInfoAsync() {
        Context context = getApplicationContext();
        NotificationHelper.registerSyncChannel(context);

        Notification notification = notificationFactory.getSyncNotification(
                "Syncing locations",
                "Sync in progress"
        );

        ForegroundInfo foregroundInfo = new ForegroundInfo(NOTIFICATION_ID, notification);
        return Futures.immediateFuture(foregroundInfo);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (isStopped()) {
            logger.warn("LocationSyncWorker canceled before initialization.");
            return Result.failure();
        }

        final Context context = getApplicationContext();

        SettingDAO settingDAO = DAOFactory.createSettingDAO(context);
        Setting setting = null;

        try {
            setting = settingDAO.retrieveSetting();
        } catch (Exception e) {
            logger.error("Failed retrieving tracking settings in worker", e);
        }

        if (setting == null || !setting.isStarted()) {
            logger.info("LocationSyncWorker: Tracking is OFF. Exiting worker cleanly.");
            return Result.success();
        }

        boolean shouldResurrectOnUpgrade = getInputData().getBoolean(INPUT_RESURRECT_SERVICE, false);
        boolean shouldResurrectOnBoot = getInputData().getBoolean(INPUT_RESURRECT_ON_BOOT, false);

        if (shouldResurrectOnUpgrade || shouldResurrectOnBoot) {
            handleServiceResurrection(context, shouldResurrectOnBoot);
        }

        ConfigurationDAO configDAO = DAOFactory.createConfigurationDAO(context);
        Config config;

        try {
            config = configDAO.retrieveConfiguration();
        } catch (Exception e) {
            logger.error("Failed parsing configuration", e);
            return Result.failure();
        }

        if (config == null || !config.hasValidSyncUrl()) {
            logger.warn("Target API endpoint URL is blank or config unavailable. Halting worker.");
            return Result.failure();
        }

        if (!isNetworkAvailable()) {
            logger.warn("Worker initiated without internet connection. Scheduling retry.");
            return Result.retry();
        }

        final boolean isForced = getInputData().getBoolean(INPUT_FORCE_SYNC, false);
        final int passedDynamicThreshold = getInputData().getInt(INPUT_DYNAMIC_SYNC_THRESHOLD, -1);

        this.notificationsEnabled = isForced
                && (!config.hasNotificationsEnabled() || config.getNotificationsEnabled());

        int initialThreshold;
        if (isForced || getRunAttemptCount() > 0) {
            initialThreshold = 0;
        } else if (passedDynamicThreshold >= 0) {
            initialThreshold = passedDynamicThreshold;
        } else {
            initialThreshold = (config.getSyncThreshold() != null && config.getSyncThreshold() > 0)
                    ? config.getSyncThreshold()
                    : 0;
        }

        int chunkCount = 0;

        // Drain pending queue chunk by chunk
        while (!isStopped()) {
            final long chunkBatchId = System.currentTimeMillis();
            int effectiveThreshold = (chunkCount == 0) ? initialThreshold : 0;
            File chunkFile = null;

            try {
                chunkFile = batchManager.createBatch(
                        chunkBatchId,
                        effectiveThreshold,
                        MAX_LOCATIONS_PER_CHUNK,
                        config.getTemplate()
                );

                if (chunkFile == null) {
                    break;
                }

                if (isStopped()) {
                    batchManager.unassignBatch(chunkBatchId);
                    cleanUpBatchFile(chunkFile);
                    return Result.retry();
                }

                Map<String, String> headers = buildHeaders(config, chunkBatchId);
                boolean success = uploadLocations(chunkFile, config.getSyncUrl(), headers);

                if (isStopped()) {
                    batchManager.unassignBatch(chunkBatchId);
                    cleanUpBatchFile(chunkFile);
                    return Result.retry();
                }

                if (success) {
                    batchManager.setBatchCompleted(chunkBatchId);
                    chunkCount++;
                    logger.info("Chunk {} uploaded and purged successfully.", chunkCount);
                } else {
                    batchManager.unassignBatch(chunkBatchId);
                    logger.warn("Chunk upload failed. Scheduling retry.");
                    return Result.retry();
                }

            } catch (IOException e) {
                logger.error("IO error while preparing/uploading offline batch", e);
                batchManager.unassignBatch(chunkBatchId);
                return Result.retry();
            } catch (Exception e) {
                logger.error("Unexpected error in LocationSyncWorker", e);
                batchManager.unassignBatch(chunkBatchId);
                return Result.retry();
            } finally {
                cleanUpBatchFile(chunkFile);
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        }

        return Result.success();
    }

    private void handleServiceResurrection(@NonNull Context context, boolean isBoot) {
        try {
            ConfigurationDAO workerConfigDao = DAOFactory.createConfigurationDAO(context);
            Config trackingConfig = workerConfigDao.retrieveConfiguration();

            boolean isAllowed = !isBoot || (trackingConfig != null && trackingConfig.getStartOnBoot());
            if (!isAllowed) {
                logger.info("Service resurrection skipped. startOnBoot is disabled.");
                return;
            }

            if (!hasLocationPermission(context)) {
                logger.warn("Service resurrection skipped because location permission is unavailable.");
                return;
            }

            Intent serviceIntent = new Intent(context, LocationServiceImpl.class);
            serviceIntent.putExtra("cmd", new LocationServiceIntentBuilder.Command(0).toBundle());
            serviceIntent.addFlags(Intent.FLAG_FROM_BACKGROUND);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    ContextCompat.startForegroundService(context, serviceIntent);
                } catch (Exception e) {
                    if (e instanceof ForegroundServiceStartNotAllowedException) {
                        logger.warn("Foreground service start deferred by OS on API 31+.");
                    } else {
                        throw e;
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent);
            } else {
                context.startService(serviceIntent);
            }

            logger.info("Worker: Location foreground service resurrection requested successfully.");
        } catch (SecurityException e) {
            logger.error("Worker was not allowed to resurrect the location service.", e);
        } catch (Exception e) {
            logger.error("Worker failed processing service resurrection.", e);
        }
    }

    @NonNull
    private Map<String, String> buildHeaders(@NonNull Config config, long batchStartMillis) {
        Map<String, String> headers = new HashMap<>();

        if (config.getHttpHeaders() != null) {
            for (Map.Entry<?, ?> entry : config.getHttpHeaders().entrySet()) {
                if (entry.getKey() == null) continue;
                String key = String.valueOf(entry.getKey());
                String value = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";
                headers.put(key, value);
            }
        }

        headers.put("x-batch-id", String.valueOf(batchStartMillis));
        return headers;
    }

    private boolean uploadLocations(@NonNull File file, @NonNull String urlString, @NonNull Map<String, String> httpHeaders) {
        if (isStopped()) {
            return false;
        }

        if (notificationsEnabled && hasNotificationPermission()) {
            NotificationHelper.registerSyncChannel(getApplicationContext());
            Notification activeNotification = notificationFactory.getSyncProgressNotification(
                    "Syncing locations",
                    "Sync in progress",
                    0
            );
            notificationManager.notify(NOTIFICATION_ID, activeNotification);
        }

        boolean isStatusOkay = false;
        String completionText = "Sync failed due to server error";
        HttpURLConnection connection = null;

        try {
            URL targetUrl = new URL(urlString);
            connection = (HttpURLConnection) targetUrl.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setUseCaches(false);

            // Gzip Request & Accept Encoding
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Encoding", "gzip");
            connection.setRequestProperty("Accept-Encoding", "gzip");

            for (Map.Entry<String, String> entry : httpHeaders.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }

            long totalBytes = file.length();
            long bytesReadTotal = 0;

            try (FileInputStream fileIn = new FileInputStream(file);
                 BufferedInputStream bufferedIn = new BufferedInputStream(fileIn);
                 OutputStream out = connection.getOutputStream();
                 GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {

                byte[] buffer = new byte[4096];
                int bytesRead;

                while ((bytesRead = bufferedIn.read(buffer)) != -1) {
                    if (isStopped()) return false;
                    gzipOut.write(buffer, 0, bytesRead);
                    bytesReadTotal += bytesRead;

                    if (totalBytes > 0) {
                        int progress = (int) ((bytesReadTotal * 100) / totalBytes);
                        onProgress(progress);
                    }
                }
                gzipOut.finish();
            }

            int responseCode = connection.getResponseCode();

            String serverDateHeader = connection.getHeaderField("Date");
            if (serverDateHeader != null) {
                long serverTime = connection.getHeaderFieldDate("Date", 0);
                if (serverTime > 0) {
                    RealTimeHelper.calibrateTime(serverTime, "ServerDateHeader");
                }
            }

            if (isStopped()) {
                return false;
            }

            isStatusOkay = responseCode >= 200 && responseCode < 300;
            completionText = isStatusOkay ? "Sync completed" : "Sync failed due to server error";

            if (responseCode == 285) {
                sendActionBroadcast(LocationServiceImpl.MSG_ON_ABORT_REQUESTED);
            } else if (responseCode == 401) {
                sendActionBroadcast(LocationServiceImpl.MSG_ON_HTTP_AUTHORIZATION);
            }

            return isStatusOkay;

        } catch (IOException e) {
            completionText = "Sync failed: " + (e.getMessage() != null ? e.getMessage() : "network error");
            logger.warn("Location batch upload failed", e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }

            if (notificationsEnabled) {
                final String finalText = completionText;
                mainThreadHandler.post(() -> {
                    try {
                        if (hasNotificationPermission()) {
                            Notification completedNotification = notificationFactory.getSyncCompletedNotification(
                                    "Syncing locations",
                                    finalText
                            );
                            notificationManager.notify(NOTIFICATION_ID, completedNotification);
                        }

                        mainThreadHandler.postDelayed(() -> {
                            try {
                                notificationManager.cancel(NOTIFICATION_ID);
                            } catch (Exception ignored) {}
                        }, 5000L);

                    } catch (Exception e) {
                        Log.w(TAG, "Error finalizing sync notification.", e);
                    }
                });
            }
        }
    }

    @Override
    public void onProgress(final int progress) {
        if (isStopped() || !notificationsEnabled || !hasNotificationPermission()) {
            return;
        }

        if (progress == 100 || progress >= lastReportedProgress + 10) {
            this.lastReportedProgress = progress;

            mainThreadHandler.post(() -> {
                try {
                    if (isStopped()) return;

                    Notification progressNotification = notificationFactory.getSyncProgressNotification(
                            "Syncing locations",
                            "Sync in progress",
                            progress
                    );
                    notificationManager.notify(NOTIFICATION_ID, progressNotification);
                } catch (Exception e) {
                    Log.e(TAG, "Failed pushing sync progress notification.", e);
                }
            });
        }
    }

    private void cleanUpBatchFile(@Nullable File file) {
        if (file == null || !file.exists()) return;
        try {
            if (!file.delete()) {
                logger.debug("Batch file could not be deleted immediately: {}", file.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing batch file cleanup.", e);
        }
    }

    @Override
    public void onStopped() {
        super.onStopped();
        try {
            mainThreadHandler.removeCallbacksAndMessages(null);
            notificationManager.cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {}
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            android.net.NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnected();
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                    getApplicationContext(),
                    Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasLocationPermission(@NonNull Context context) {
        return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void sendActionBroadcast(int actionId) {
        Bundle bundle = new Bundle();
        bundle.putInt("action", actionId);

        Intent intent = new Intent(LocationServiceImpl.ACTION_BROADCAST);
        intent.putExtras(bundle);

        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }
}
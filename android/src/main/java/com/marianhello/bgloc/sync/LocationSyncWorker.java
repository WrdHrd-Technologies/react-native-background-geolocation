package com.marianhello.bgloc.sync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.HttpPostService;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.logging.LoggerManager;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

public class LocationSyncWorker extends Worker implements HttpPostService.UploadingProgressListener {

    private static final int NOTIFICATION_ID = 666;
    private final org.slf4j.Logger logger;
    
    private ConfigurationDAO configDAO;
    private BatchManager batchManager;
    private NotificationManager notificationManager;
    private boolean notificationsEnabled = true;
    private int lastReportedProgress = -1;

    public LocationSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        logger = LoggerManager.getLogger(LocationSyncWorker.class);
        
        configDAO = DAOFactory.createConfigurationDAO(context);
        batchManager = new BatchManager(context);
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @NonNull
    @Override
    public Result doWork() {
        Config config = null;
        try {
            config = configDAO.retrieveConfiguration();
        } catch (Exception e) {
            logger.error("Error retrieving config: {}", e.getMessage());
            return Result.failure();
        }

        if (config == null || !config.hasValidSyncUrl()) {
            logger.warn("Invalid sync URL. Aborting worker.");
            return Result.failure();
        }

        

        Long batchStartMillis = System.currentTimeMillis();

        boolean isForced = getInputData().getBoolean("force_sync", false);

        notificationsEnabled = isForced && (!config.hasNotificationsEnabled() || config.getNotificationsEnabled());
        
        // WorkManager retries automatically. If runAttemptCount > 0, it's a retry.
        int syncThreshold = (isForced || getRunAttemptCount() > 0) ? 0 : config.getSyncThreshold();
        logger.debug("Sync request runAttempt: {}, batchId: {}", getRunAttemptCount(), batchStartMillis);

        File file;
        try {
            file = batchManager.createBatch(batchStartMillis, syncThreshold, config.getTemplate());
        } catch (IOException e) {
            logger.error("Failed to create batch: {}", e.getMessage());
            return Result.retry(); 
        }

        if (file == null) {
            logger.info("Nothing to sync. Database is empty.");
            return Result.success();
        }

        logger.info("Syncing startAt: {}", batchStartMillis);
        String url = config.getSyncUrl();
        HashMap<String, String> httpHeaders = new HashMap<>();
        httpHeaders.putAll(config.getHttpHeaders());
        httpHeaders.put("x-batch-id", String.valueOf(batchStartMillis));

        // EXECUTE UPLOAD
        boolean success = uploadLocations(file, url, httpHeaders);

        if (success) {
            logger.info("Batch sync successful");
            batchManager.setBatchCompleted(batchStartMillis);
            if (file.delete()) {
                logger.info("Batch file deleted.");
            }
            return Result.success();
        } else {
            logger.warn("Batch sync failed. Telling WorkManager to reschedule.");
            // WorkManager will automatically apply exponential backoff (e.g., wait 30s, then 1m, then 2m)
            return Result.retry(); 
        }
    }

    private boolean uploadLocations(File file, String url, HashMap<String, String> httpHeaders) {
        NotificationCompat.Builder builder = null;

        if (notificationsEnabled) {
            builder = new NotificationCompat.Builder(getApplicationContext(), NotificationHelper.SYNC_CHANNEL_ID);
            builder.setOngoing(true);
            builder.setContentTitle("Syncing locations");
            builder.setContentText("Sync in progress");
            builder.setSmallIcon(android.R.drawable.ic_dialog_info);
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        }

        try {
            int responseCode = HttpPostService.postJSONFile(url, file, httpHeaders, this);
            boolean isStatusOkay = responseCode >= 200 && responseCode < 300;

            // 285: Server commands us to stop tracking
            if (responseCode == 285) {
                logger.warn("HTTP 285 received. Server requested tracking abort.");
                Bundle bundle = new Bundle();
                bundle.putInt("action", LocationServiceImpl.MSG_ON_ABORT_REQUESTED);
                broadcastMessage(bundle);
            }

            // 401: Token expired
            if (responseCode == 401) {
                logger.warn("HTTP 401 received. Token unauthorized.");
                Bundle bundle = new Bundle();
                bundle.putInt("action", LocationServiceImpl.MSG_ON_HTTP_AUTHORIZATION);
                broadcastMessage(bundle);
            }

            if (builder != null) {
                builder.setContentText(isStatusOkay ? "Sync completed" : "Sync failed due to server error");
            }

            return isStatusOkay;
            
        } catch (IOException e) {
            logger.warn("Error uploading locations: {}", e.getMessage());
            if (builder != null) builder.setContentText("Sync failed: " + e.getMessage());
            return false;
        } finally {
            if (builder != null) {
                builder.setOngoing(false);
                builder.setProgress(0, 0, false);
                builder.setAutoCancel(true);
                notificationManager.notify(NOTIFICATION_ID, builder.build());

                Handler h = new Handler(Looper.getMainLooper());
                h.postDelayed(() -> notificationManager.cancel(NOTIFICATION_ID), 5000);
            }
        }
    }

    @Override
    public void onProgress(int progress) {
        if (!notificationsEnabled) return;

        if (progress == 100 || progress >= lastReportedProgress + 10) {
            lastReportedProgress = progress;
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), NotificationHelper.SYNC_CHANNEL_ID)
                    .setOngoing(true)
                    .setContentTitle("Syncing locations")
                    .setContentText("Sync in progress")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOnlyAlertOnce(true) 
                    .setProgress(100, progress, false);
            
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        }
    }

    private void broadcastMessage(Bundle bundle) {
        Intent intent = new Intent(LocationServiceImpl.ACTION_BROADCAST);
        intent.putExtras(bundle);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }
}
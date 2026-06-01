package com.marianhello.bgloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.bgloc.service.LocationServiceIntentBuilder;
import com.marianhello.bgloc.service.HeartbeatManager;


public final class HeartbeatReceiver extends BroadcastReceiver {
    private static final String TAG = "HeartbeatReceiver";
    private static final String KEY_COMMAND = "cmd";

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        Log.d(TAG, "Headless hardware tracking pulse registered. Processing dispatch chain.");

        Context appContext = context.getApplicationContext();

        try {
            com.marianhello.bgloc.service.HeartbeatManager heartbeatManager = 
                new com.marianhello.bgloc.service.HeartbeatManager(appContext);
            
            if (heartbeatManager.isRunning()) {
                heartbeatManager.start(); 
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed re-arming the periodic heartbeat monitoring line.", e);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                OneTimeWorkRequest syncRequest = new OneTimeWorkRequest.Builder(
                        com.marianhello.bgloc.sync.LocationSyncWorker.class)
                        .setInputData(new Data.Builder().putBoolean("force_sync", true).build())
                        .build();

                WorkManager.getInstance(appContext).enqueue(syncRequest);
                Log.i(TAG, "Asynchronous sync worker dispatched to handle background tracking pulse cleanly.");
                return; 
            } catch (Exception ex) {
                Log.e(TAG, "WorkManager delegation faltered. Falling back to native routing matrix.", ex);
            }
        }

        Intent locationServiceIntent = new Intent(appContext, LocationServiceImpl.class);
        LocationServiceIntentBuilder.Command cmd = new LocationServiceIntentBuilder.Command(9);
        locationServiceIntent.putExtra(KEY_COMMAND, cmd.toBundle());

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(locationServiceIntent);
            } else {
                appContext.startService(locationServiceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Fatal restriction block: System rejected foreground orchestration intent.", e);
        }
    }
}
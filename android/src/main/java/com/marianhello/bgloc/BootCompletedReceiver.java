package com.marianhello.bgloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import com.marianhello.bgloc.sync.LocationSyncWorker;
import com.marianhello.utils.RealTimeHelper;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) 
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        Log.i(TAG, "Device boot completed [" + action + "]. Offloading tracking recovery to WorkManager.");

        Context appContext = context.getApplicationContext();
        RealTimeHelper.initialize(appContext);

        Data inputData = new Data.Builder()
                .putBoolean("resurrect_on_boot", true)
                .build();

        OneTimeWorkRequest bootRequest = new OneTimeWorkRequest.Builder(LocationSyncWorker.class)
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();

        try {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                    "LocationSyncJob",
                    ExistingWorkPolicy.REPLACE, 
                    bootRequest
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule tracking boot restoration pipeline with WorkManager.", e);
        }
    }
}
package com.marianhello.bgloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import com.marianhello.bgloc.sync.LocationSyncWorker;
import com.marianhello.utils.RealTimeHelper;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = BootCompletedReceiver.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        Log.i(TAG, "Device boot completed. Offloading verification and tracking recovery to WorkManager.");

        RealTimeHelper.initialize(context);

        Data inputData = new Data.Builder()
                .putBoolean("resurrect_on_boot", true)
                .build();

        OneTimeWorkRequest bootRequest = new OneTimeWorkRequest.Builder(LocationSyncWorker.class)
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();

        try {
            WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                    "LocationSyncJob",
                    ExistingWorkPolicy.REPLACE, 
                    bootRequest
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule tracking boot restoration pipeline with WorkManager.", e);
        }
    }
}
package com.marianhello.bgloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;

import com.marianhello.bgloc.sync.LocationSyncWorker;
import com.marianhello.bgloc.sync.WorkManagerHelper;
import com.marianhello.utils.RealTimeHelper;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BootCompletedReceiver";

    // OEM-specific power-on / boot intents
    private static final String ACTION_QUICKBOOT_POWERON_1 = "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_QUICKBOOT_POWERON_2 = "com.htc.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_HUAWEI_POWERON = "com.huawei.intent.action.POWER_ON";
    private static final String ACTION_MIUI_FORCE_START = "com.miui.action.FORCE_START";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        String action = intent.getAction();
        if (action == null) {
            return;
        }

        boolean isBootAction = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON_1.equals(action)
                || ACTION_QUICKBOOT_POWERON_2.equals(action)
                || ACTION_HUAWEI_POWERON.equals(action)
                || ACTION_MIUI_FORCE_START.equals(action)
                || Intent.ACTION_REBOOT.equals(action);

        boolean isPackageUpdate = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        if (!isBootAction && !isPackageUpdate) {
            return;
        }

        Context appContext = context.getApplicationContext();

        // Direct Boot Guard: Verify Credential Encrypted (CE) storage accessibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager userManager = (UserManager) appContext.getSystemService(Context.USER_SERVICE);
            if (userManager != null && !userManager.isUserUnlocked()) {
                Log.w(TAG, "System event [" + action + "] received in locked state. Deferring execution.");
                return;
            }
        }

        Log.i(TAG, "Processing system event [" + action + "]. Offloading resurrection to WorkManager.");

        try {
            RealTimeHelper.initialize(appContext);
        } catch (Exception e) {
            Log.w(TAG, "RealTime initialization skipped during boot sequence.", e);
        }

        Data inputData = new Data.Builder()
                .putBoolean(LocationSyncWorker.INPUT_RESURRECT_ON_BOOT, isBootAction)
                .putBoolean(LocationSyncWorker.INPUT_RESURRECT_SERVICE, isPackageUpdate)
                .build();

        OneTimeWorkRequest resurrectionRequest = new OneTimeWorkRequest.Builder(LocationSyncWorker.class)
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();

        try {
            WorkManagerHelper.getWorkManager(appContext).enqueueUniqueWork(
                    "LocationSyncJob",
                    ExistingWorkPolicy.REPLACE,
                    resurrectionRequest
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed scheduling tracking restoration via WorkManagerHelper.", e);
        }
    }
}
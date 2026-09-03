package com.marianhello.bgloc.sync;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.WorkManager;

public final class WorkManagerHelper {

    private WorkManagerHelper() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    @NonNull
    public static WorkManager getWorkManager(@NonNull Context context) {
        return WorkManager.getInstance(context.getApplicationContext());
    }
}
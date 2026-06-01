package com.marianhello.bgloc.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class AutoStartHelper {
    private static final String TAG = "AutoStartHelper";

    private static final String BRAND_XIAOMI = "xiaomi";
    private static final String BRAND_POCO = "poco";
    private static final String BRAND_REDMI = "redmi";
    private static final String BRAND_LETV = "letv";
    private static final String BRAND_ASUS = "asus";
    private static final String BRAND_HONOR = "honor";
    private static final String BRAND_HUAWEI = "huawei";
    private static final String BRAND_OPPO = "oppo";
    private static final String BRAND_REALME = "realme";
    private static final String BRAND_VIVO = "vivo";
    private static final String BRAND_IQOO = "iqoo";
    private static final String BRAND_NOKIA = "nokia";

    private String alertLabel = "Background Tracking Optimization";
    private String alertMessage = "Please enable 'Auto-Start' or 'Background Protection' settings to ensure reliable automated coordinate synchronization.";
    private String allowButtonLabel = "Configure";

    public AutoStartHelper(String label, String message, String allowButtonLabel) {
        this.alertLabel = label;
        this.alertMessage = message;
        this.allowButtonLabel = allowButtonLabel;
    }

    public AutoStartHelper() {}

    private List<Intent> getOemIntentsByBrand(String brand) {
        List<Intent> intents = new ArrayList<>();

        switch (brand) {
            case BRAND_ASUS:
                intents.add(new Intent().setComponent(new ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings")));
                break;

            case BRAND_XIAOMI:
            case BRAND_POCO:
            case BRAND_REDMI:
                intents.add(new Intent().setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")));
                break;

            case BRAND_LETV:
                intents.add(new Intent().setComponent(new ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")));
                break;

            case BRAND_HONOR:
            case BRAND_HUAWEI:
                intents.add(new Intent().setComponent(new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")));
                break;

            case BRAND_OPPO:
            case BRAND_REALME:
                intents.add(new Intent().setComponent(new ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")));
                // Fallbacks
                intents.add(new Intent().setComponent(new ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")));
                intents.add(new Intent().setComponent(new ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")));
                break;

            case BRAND_VIVO:
            case BRAND_IQOO:
                intents.add(new Intent().setComponent(new ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")));
                // Fallbacks
                intents.add(new Intent().setComponent(new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")));
                intents.add(new Intent().setComponent(new ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")));
                break;

            case BRAND_NOKIA:
                intents.add(new Intent().setComponent(new ComponentName("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity")));
                break;
        }

        return intents;
    }

    private Intent resolveValidOemIntent(Context context) {
        String brand = Build.BRAND.toLowerCase();
        List<Intent> candidates = getOemIntentsByBrand(brand);

        for (Intent intent : candidates) {
            if (isIntentResolvable(context, intent)) {
                return intent;
            }
        }
        return null;
    }

    public boolean isAutoStartSupportedOnDevice(Context context) {
        return resolveValidOemIntent(context) != null;
    }

    public void getAutoStartPermission(Context context) {
        if (!(context instanceof Activity)) {
            Log.w(TAG, "Context validation failed: AutoStart interface requires an active foreground Window instance.");
            return;
        }

        final Activity activity = (Activity) context;
        if (activity.isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
            return;
        }

        // Pull the valid executable target directly from our unified resolver method
        final Intent executableIntent = resolveValidOemIntent(activity);

        if (executableIntent != null) {
            showAlert(activity, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        activity.startActivity(executableIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to launch resolved OEM intent target", e);
                    }
                }
            });
        } else {
            Log.d(TAG, "No valid Auto-Start configurations found or resolvable for brand: " + Build.BRAND);
        }
    }

    private void showAlert(Context context, DialogInterface.OnClickListener onClickListener) {
        new AlertDialog.Builder(context)
                .setTitle(this.alertLabel)
                .setMessage(this.alertMessage)
                .setPositiveButton(this.allowButtonLabel, onClickListener)
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .show();
    }

    private boolean isIntentResolvable(Context context, Intent intent) {
        try {
            PackageManager pm = context.getPackageManager();
            return intent.resolveActivity(pm) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
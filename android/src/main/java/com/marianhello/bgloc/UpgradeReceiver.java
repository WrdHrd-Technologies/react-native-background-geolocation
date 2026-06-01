package com.marianhello.bgloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.SettingDAO;
import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.bgloc.service.LocationServiceIntentBuilder;
import com.marianhello.utils.RealTimeHelper;
import org.json.JSONException;

public class UpgradeReceiver extends BroadcastReceiver {
    private static final String TAG = UpgradeReceiver.class.getName();
    private static final String KEY_COMMAND = "cmd";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            return;
        }

        Log.i(TAG, "Application updated or reinstalled. Reviewing persistent tracking status.");

        RealTimeHelper.initialize(context);

        ConfigurationDAO dao = DAOFactory.createConfigurationDAO(context);
        SettingDAO settingDao = DAOFactory.createSettingDAO(context);
        Config config = null;
        Setting setting = null;

        try {
            config = dao.retrieveConfiguration();
        } catch (JSONException e) {
            Log.e(TAG, "Configuration parsing failed during update execution context.", e);
        }

        try {
            setting = settingDao.retrieveSetting();
        } catch (JSONException e) {
            Log.w(TAG, "Setting retrieval failed during update execution, matching fallback defaults.", e);
            setting = Setting.getDefault();
        }

        if (config == null || setting == null) {
            Log.w(TAG, "Abort update auto-restart: Missing runtime persistence dependencies.");
            return;
        }

        if (setting.isStarted()) {
            Log.i(TAG, "Persistence states matched. Reviving tracking service following package upgrade.");

            Intent locationServiceIntent = new Intent(context, LocationServiceImpl.class);
            LocationServiceIntentBuilder.Command cmd = new LocationServiceIntentBuilder.Command(0); // Match command 0

            locationServiceIntent.putExtra(KEY_COMMAND, cmd.toBundle());
            locationServiceIntent.addFlags(Intent.FLAG_FROM_BACKGROUND);
            locationServiceIntent.putExtra("config", config);

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(locationServiceIntent);
                } else {
                    context.startService(locationServiceIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Operating System actively blocked background service initialization sequence post-update.", e);
            }
        } else {
            Log.d(TAG, "Ignored update broadcast: Tracking service state was not active before upgrade.");
        }
    }
}
/*
According to apache license

This is fork of christocracy cordova-plugin-background-geolocation plugin
https://github.com/christocracy/cordova-plugin-background-geolocation

This is a new class
*/

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

/**
 * BootCompletedReceiver class
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = BootCompletedReceiver.class.getName();
    private static final String KEY_COMMAND = "cmd";

    @Override
     public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Received boot completed");

        RealTimeHelper.initialize(context);

        ConfigurationDAO dao = DAOFactory.createConfigurationDAO(context);
        SettingDAO settingDao = DAOFactory.createSettingDAO(context);
        Config config = null;
        Setting setting = null;


        try {
            config = dao.retrieveConfiguration();
        } catch (JSONException e) {
            //noop
        }

        try {
            setting = settingDao.retrieveSetting();
        } catch (JSONException e) {
           setting = Setting.getDefault();
        }

        if (config == null) { return; }
        if (setting == null) { return; }

        Log.d(TAG, "Boot completed " + config.toString());

        if (config.getStartOnBoot() && setting.isStarted()) {
            Log.i(TAG, "Starting service after boot");
            Intent locationServiceIntent = new Intent(context, LocationServiceImpl.class);
            LocationServiceIntentBuilder.Command cmd = new LocationServiceIntentBuilder.Command(0);
            locationServiceIntent.putExtra(KEY_COMMAND, cmd.toBundle());
            locationServiceIntent.addFlags(Intent.FLAG_FROM_BACKGROUND);
            locationServiceIntent.putExtra("config", config);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(locationServiceIntent);
            } else {
                context.startService(locationServiceIntent);
            }
        }
     }
}

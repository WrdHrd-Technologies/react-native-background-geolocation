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
 * HeartbeatReceiver class
 */
public class HeartbeatReceiver extends BroadcastReceiver {
    private static final String TAG = HeartbeatReceiver.class.getName();
    private static final String KEY_COMMAND = "cmd";

    @Override
     public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Heartbeat received");
        Intent locationServiceIntent = new Intent(context, LocationServiceImpl.class);
        LocationServiceIntentBuilder.Command cmd = new LocationServiceIntentBuilder.Command(9);
        locationServiceIntent.putExtra(KEY_COMMAND, cmd.toBundle());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(locationServiceIntent);
        } else {
            context.startService(locationServiceIntent);
        }
     }
}

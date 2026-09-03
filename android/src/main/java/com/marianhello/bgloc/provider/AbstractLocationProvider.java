package com.marianhello.bgloc.provider;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.location.DetectedActivity;
import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.PluginException;
import com.marianhello.bgloc.data.BackgroundActivity;
import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.BatteryInfo;
import com.marianhello.bgloc.data.BatteryUtils;
import com.marianhello.logging.LoggerManager;
import com.marianhello.utils.RealTimeHelper;
import com.marianhello.utils.ToneGenerator;
import com.marianhello.utils.ToneGenerator.Tone;

public abstract class AbstractLocationProvider implements LocationProvider {
    private static final String TAG = "AbstractLocProvider";

    protected final Integer PROVIDER_ID;
    protected Config mConfig;
    protected final Context mContext; 

    protected ToneGenerator toneGenerator;
    protected final org.slf4j.Logger logger;

    private ProviderDelegate mDelegate;
    private Location lastLocation;

    protected AbstractLocationProvider(@NonNull Context context, Integer provider_id) {
        mContext = context.getApplicationContext();
        logger = LoggerManager.getLogger(getClass());
        this.PROVIDER_ID = provider_id;
        logger.info("Creating {}", getClass().getSimpleName());
    }

    @Override
    public void onCreate() {
        toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
    }

    @Override
    public void onStart() {}

    @Override
    public void onStop() {}

    @Override
    public void onDestroy() {
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    @Override
    public void onConfigure(Config config) {
        mConfig = config;
    }

    @Override
    public void onCommand(int commandId, int arg1) {}

    public void setDelegate(ProviderDelegate delegate) {
        mDelegate = delegate;
    }

    protected Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { 
            return mContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        return mContext.registerReceiver(receiver, filter);
    }

    protected void unregisterReceiver(BroadcastReceiver receiver) {
        try {
            mContext.unregisterReceiver(receiver);
        } catch (Exception e) {
            Log.w(TAG, "Unregister receiver invoked out-of-bounds or receiver already dead.", e);
        }
    }

    /**
     * Unified mapper constructing a complete BackgroundLocation entity with 
     * tamper-proof monotonic time, cached battery status, and mock flags.
     */
    @NonNull
    protected BackgroundLocation buildBackgroundLocation(@NonNull Location location) {
        BackgroundLocation bgLocation = BackgroundLocation.fromLocation(location);
        bgLocation.setLocationProvider(PROVIDER_ID);

        bgLocation.setRealTime(RealTimeHelper.nowMillis());

        BatteryInfo batteryInfo = BatteryUtils.getBatteryStatus(mContext);
        bgLocation.setBatteryLevel(batteryInfo.getBatteryLevel());
        bgLocation.setIsCharging(batteryInfo.getIsCharging());

        bgLocation.setMockLocationsEnabled(hasMockLocationsEnabled(location));

        return bgLocation;
    }

    protected void handleLocation(Location location) {
        if (location == null) return;
        playDebugTone(Tone.BEEP);
        
        if (mDelegate != null) {
            if (lastLocation != null && lastLocation.getTime() == location.getTime()) {
                return;
            }
            lastLocation = location;

            BackgroundLocation bgLocation = buildBackgroundLocation(location);
            mDelegate.onLocation(bgLocation);
        }
    }

    protected void handleStationary(Location location, float radius) {
        if (location == null) return;
        playDebugTone(Tone.LONG_BEEP);
        
        if (mDelegate != null) {
            BackgroundLocation bgLocation = buildBackgroundLocation(location);
            bgLocation.setRadius(radius);
            mDelegate.onStationary(bgLocation);
        }
    }

    protected void handleStationary(Location location) {
        handleStationary(location, 0.0f);
    }

    protected void handleActivity(DetectedActivity activity) {
        if (mDelegate != null && activity != null) {
            mDelegate.onActivity(new BackgroundActivity(PROVIDER_ID, activity));
        }
    }

    protected void handleSecurityException(SecurityException exception) {
        PluginException error = new PluginException(exception.getMessage(), PluginException.PERMISSION_DENIED_ERROR);
        if (mDelegate != null) {
            mDelegate.onError(error);
        }
    }

    protected void showDebugToast(String text) {
        if (mConfig != null && mConfig.isDebugging()) {
            Toast.makeText(mContext, text, Toast.LENGTH_LONG).show();
        }
    }

    public Boolean hasMockLocationsEnabled(@Nullable Location location) {
        if (location == null) {
            return false;
        }

        // Android 12 (API 31) through Android 16+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { 
            return location.isMock();
        }
    
        // Android 6.0 (API 23) to Android 11 (API 30)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return location.isFromMockProvider();
        }

        // Android 5.1 and below (Legacy fallback)
        try {
            String value = Settings.Secure.getString(mContext.getContentResolver(), "mock_location");
            return "1".equals(value);
        } catch (Exception e) {
            return false;
        }
    }

    protected void playDebugTone(int name) {
        if (toneGenerator == null || mConfig == null || !mConfig.isDebugging()) return;
        try {
            toneGenerator.startTone(name, 1000);
        } catch (Exception e) {
            Log.w(TAG, "Failed to output audio debug tone.", e);
        }
    }

    @Override
    public void onResume() {}
}
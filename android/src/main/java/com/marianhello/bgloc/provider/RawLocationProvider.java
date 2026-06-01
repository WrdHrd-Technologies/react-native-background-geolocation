package com.marianhello.bgloc.provider;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.marianhello.bgloc.Config;

public class RawLocationProvider extends AbstractLocationProvider implements LocationListener {
    private static final String TAG = "RawLocationProvider";
    private LocationManager locationManager;
    private boolean isStarted = false;

    public RawLocationProvider(Context context) {
        super(context, Config.RAW_PROVIDER);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public void onStart() {
        if (isStarted) {
            return;
        }

        if (locationManager == null) {
            Log.e(TAG, "Initialization failed: Hardware location subsystem unavailable.");
            return;
        }

        Criteria hardwareCriteria = new Criteria();
        hardwareCriteria.setAccuracy(translateDesiredAccuracy(mConfig.getDesiredAccuracy()));
        hardwareCriteria.setCostAllowed(true);
        hardwareCriteria.setPowerRequirement(Criteria.POWER_MEDIUM);

        String bestProvider = locationManager.getBestProvider(hardwareCriteria, true);
        if (bestProvider == null) {
            bestProvider = LocationManager.GPS_PROVIDER; 
        }

        try {
            super.onStart();
            logger.info("Engaging Raw Location Updates via provider engine [{}]. Interval: {}ms. Distance Filter: {}m", 
                    bestProvider, mConfig.getInterval(), mConfig.getDistanceFilter());

            locationManager.requestLocationUpdates(
                    bestProvider, 
                    mConfig.getInterval(), 
                    mConfig.getDistanceFilter(), 
                    this, 
                    Looper.getMainLooper()
            );
            
            isStarted = true;
        } catch (SecurityException e) {
            logger.error("Platform execution blocked: Runtime location tracing privileges absent.", e);
            this.handleSecurityException(e);
        } catch (Exception ex) {
            logger.error("Unexpected failure occurred during native hardware provider engagement sequence.", ex);
        }
    }

    @Override
    public void onStop() {
        if (!isStarted) {
            return;
        }
        try {
            super.onStop();
            logger.info("Halting native platform hardware coordinate listener loops.");
            locationManager.removeUpdates(this);
        } catch (SecurityException e) {
            logger.error("Security authorization exception on stop hook execution: {}", e.getMessage());
            this.handleSecurityException(e);
        } finally {
            isStarted = false;
        }
    }

    @Override
    public void onConfigure(Config config) {
        super.onConfigure(config);
        if (isStarted) {
            onStop();
            onStart();
        }
    }

    @Override
    public boolean isStarted() {
        return isStarted;
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        logger.debug("Raw hardware location packet captured: {}", location.toString());

        showDebugToast("acy:" + location.getAccuracy() + ",v:" + location.getSpeed());
        
        handleLocation(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle bundle) {
        logger.debug("Provider {} runtime operational status shifted to code: {}", provider, status);
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
        logger.info("Hardware tracking provider [{}] was enabled by user.", provider);
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
        logger.warn("Hardware tracking provider [{}] was disabled by user or device profile settings.", provider);
    }

    private int translateDesiredAccuracy(Integer accuracy) {
        if (accuracy == null) {
            return Criteria.ACCURACY_MEDIUM;
        }
        if (accuracy >= 1000) {
            return Criteria.ACCURACY_LOW;
        }
        if (accuracy >= 100) {
            return Criteria.ACCURACY_MEDIUM;
        }
        return Criteria.ACCURACY_HIGH;
    }

    @Override
    public void onDestroy() {
        logger.debug("Tearing down native RawLocationProvider core tracking stack.");
        this.onStop();
        super.onDestroy();
    }
}
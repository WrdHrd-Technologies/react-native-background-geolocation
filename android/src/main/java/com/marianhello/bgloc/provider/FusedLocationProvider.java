package com.marianhello.bgloc.provider;


import android.content.Context;
import android.location.Location;

import androidx.annotation.NonNull;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.marianhello.bgloc.Config;

/**
 * Created by
 */

public class FusedLocationProvider extends AbstractLocationProvider implements LocationListener {
    private FusedLocationProviderClient client;
    public LocationRequest locationRequest;
    private boolean isStarted = false;

    public FusedLocationProvider(Context context) {
        super(context,Config.FUSED_PROVIDER);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        client = LocationServices.getFusedLocationProviderClient(this.mContext);
    }

    @Override
    public void onStart() {
        if (isStarted) {
            return;
        }
        try {
            super.onStart();
            logger.error("Starting Location Update with : Interval : {} : Distance Filter {} ", mConfig.getInterval(), mConfig.getDistanceFilter());
            locationRequest = new LocationRequest.Builder( translateDesiredAccuracy(mConfig.getDesiredAccuracy()), mConfig.getInterval())
                    .setWaitForAccurateLocation(true)
                    .setMinUpdateDistanceMeters(mConfig.getDistanceFilter())
                    .setMinUpdateIntervalMillis(mConfig.getFastestInterval())
                    .build();
            client.requestLocationUpdates(locationRequest,this,null);
            isStarted = true;
        } catch (SecurityException e) {
            logger.error("Security exception: {}", e.getMessage());
            this.handleSecurityException(e);
        }
    }

    @Override
    public void onStop() {
        if (!isStarted) {
            return;
        }
        try {
            super.onStop();
            logger.error("Stopping location Update");
            client.removeLocationUpdates(this);
        } catch (SecurityException e) {
            logger.error("Security exception: {}", e.getMessage());
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

    /**
     * Translates a number representing desired accuracy of Geolocation system from set [0, 10, 100, 1000].
     * 0:  most aggressive, most accurate, worst battery drain
     * 1000:  least aggressive, least accurate, best for battery.
     */
    private Integer translateDesiredAccuracy(Integer accuracy) {
        if (accuracy >= 1000) {
            return Priority.PRIORITY_LOW_POWER;
        }
        if (accuracy >= 100) {
            return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        }
        if (accuracy >= 10) {
            return Priority.PRIORITY_HIGH_ACCURACY;
        }
        if (accuracy >= 0) {
            return Priority.PRIORITY_HIGH_ACCURACY;
        }

        return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
    }

    @Override
    public void onDestroy() {
        logger.debug("Destroying RawLocationProvider");
        this.onStop();
        super.onDestroy();
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        logger.debug("Location change: {}", location.toString());

        showDebugToast("acy:" + location.getAccuracy() + ",v:" + location.getSpeed() + ",Fused Provider");
        handleLocation(location);
    }
}

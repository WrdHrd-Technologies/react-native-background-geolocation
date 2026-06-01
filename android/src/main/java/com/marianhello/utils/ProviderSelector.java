package com.marianhello.utils;

import android.location.LocationManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.location.Priority;
import com.marianhello.bgloc.Config;

import java.util.List;

public final class ProviderSelector {
    private static final String TAG = "ProviderSelector";

    private ProviderSelector() {
        throw new UnsupportedOperationException("Resolution matrix utility layers cannot be instantiated.");
    }

   
    public static int translateDesiredAccuracyToPriority(@Nullable Integer accuracy) {
        if (accuracy == null) {
            return Priority.PRIORITY_BALANCED_POWER_ACCURACY; 
        }

        if (accuracy >= 1000) {
            return Priority.PRIORITY_PASSIVE;
        }
        if (accuracy >= 100) {
            return Priority.PRIORITY_LOW_POWER; 
        }
        if (accuracy >= 10) {
            return Priority.PRIORITY_BALANCED_POWER_ACCURACY; 
        }
        
        return Priority.PRIORITY_HIGH_ACCURACY; 
    }

 
    @NonNull
    public static String getBestLegacyProvider(@Nullable LocationManager locationManager, @Nullable Config config) {
        if (locationManager == null) {
            Log.w(TAG, "LocationManager context missing. Defaulting tracking string to passive channels.");
            return LocationManager.PASSIVE_PROVIDER;
        }

        int targetAccuracy = (config != null) ? config.getDesiredAccuracy() : 100;
        int fusedPriority = translateDesiredAccuracyToPriority(targetAccuracy);

        List<String> availableProviders = locationManager.getProviders(true);
        
        boolean hasGps = availableProviders.contains(LocationManager.GPS_PROVIDER);
        boolean hasNetwork = availableProviders.contains(LocationManager.NETWORK_PROVIDER);

        if (fusedPriority == Priority.PRIORITY_HIGH_ACCURACY && hasGps) {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
        }

        if (hasNetwork && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return LocationManager.NETWORK_PROVIDER;
        }

        if (!availableProviders.isEmpty()) {
            for (String alternateProvider : availableProviders) {
                if (!LocationManager.PASSIVE_PROVIDER.equals(alternateProvider)) {
                    return alternateProvider; 
                }
            }
        }

        return LocationManager.PASSIVE_PROVIDER;
    }
}
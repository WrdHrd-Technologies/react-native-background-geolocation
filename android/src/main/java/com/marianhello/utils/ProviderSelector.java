package com.marianhello.utils;

import android.location.Criteria;
import android.location.LocationManager;

import com.marianhello.bgloc.Config;

/**
 * Created by.
 */

public class ProviderSelector {

    /**
     * Translates a number representing desired accuracy of Geolocation system from set [0, 10, 100, 1000].
     * 0:  most aggressive, most accurate, worst battery drain
     * 1000:  least aggressive, least accurate, best for battery.
     */
    private static Integer translateDesiredAccuracy(Integer accuracy) {
        if (accuracy >= 1000) {
            return Criteria.ACCURACY_LOW;
        }
        if (accuracy >= 100) {
            return Criteria.ACCURACY_MEDIUM;
        }
        if (accuracy >= 10) {
            return Criteria.ACCURACY_HIGH;
        }
        if (accuracy >= 0) {
            return Criteria.ACCURACY_HIGH;
        }

        return Criteria.ACCURACY_MEDIUM;
    }
    public static String getBestProvider(LocationManager locationManager, Config mConfig){
        // Use NETWORK_PROVIDER if, NETWORK_PROVIDER available, GPS_PROVIDER if GPS_PROVIDER enabled
        String provider = LocationManager.NETWORK_PROVIDER;
        Integer accuracyTraslated = ProviderSelector.translateDesiredAccuracy(mConfig.getDesiredAccuracy());

        // Check if GPS_PROVIDER is enabled
        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        //boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if(accuracyTraslated == Criteria.ACCURACY_HIGH) {
            if (isGpsEnabled) provider = LocationManager.GPS_PROVIDER;
        }

        return provider;
    }
}

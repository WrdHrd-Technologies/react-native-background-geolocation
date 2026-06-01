package com.marianhello.bgloc.provider;

import android.content.Context;
import android.util.Log;

import com.marianhello.bgloc.Config;
import com.tenforwardconsulting.bgloc.DistanceFilterLocationProvider;
import com.wrdhrd.bgloc.provider.FusedDistanceFilterLocationProvider;


public class LocationProviderFactory {
    private static final String TAG = "LocationProviderFactory";
    private final Context mContext;

    public LocationProviderFactory(Context context) {
        this.mContext = context.getApplicationContext();
    }

    public LocationProvider getInstance(Integer locationProvider) {
        if (locationProvider == null) {
            Log.w(TAG, "Provider mapping initialization missing. Falling back to default baseline FUSED provider.");
            return new FusedLocationProvider(mContext);
        }

        LocationProvider provider;
        
        switch (locationProvider) {
            case Config.DISTANCE_FILTER_PROVIDER:
                provider = new DistanceFilterLocationProvider(mContext);
                break;
                
            case Config.ACTIVITY_PROVIDER:
                provider = new ActivityRecognitionLocationProvider(mContext);
                break;
                
            case Config.RAW_PROVIDER:
                provider = new RawLocationProvider(mContext);
                break;
                
            case Config.FUSED_PROVIDER:
                provider = new FusedLocationProvider(mContext);
                break;
                
            case Config.FUSED_DISTANCE_FILTER_PROVIDER:
                provider = new FusedDistanceFilterLocationProvider(mContext);
                break;
                
            default:
                Log.e(TAG, "Fatal engine execution requested: Target location provider ID [" + locationProvider + "] is unmapped.");
                throw new IllegalArgumentException("Provider mapping configuration target not found: " + locationProvider);
        }

        return provider;
    }
}
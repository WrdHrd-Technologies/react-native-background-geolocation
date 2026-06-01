package com.marianhello.bgloc;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class ResourceResolver {
    private static final String TAG = "ResourceResolver";
    private static final String RESOURCE_PREFIX = "mauron85_bgloc_";
    
    private static final String ACCOUNT_NAME_RESOURCE = RESOURCE_PREFIX + "account_name";
    private static final String ACCOUNT_TYPE_RESOURCE = RESOURCE_PREFIX + "account_type";
    private static final String AUTHORITY_TYPE_RESOURCE = RESOURCE_PREFIX + "content_authority";

    private static final Map<String, Integer> RESOURCE_CACHE = new ConcurrentHashMap<>();

    private final Context mContext;

    ResourceResolver() {
        this.mContext = null;
    }

    private ResourceResolver(@NonNull Context context) {
        this.mContext = context.getApplicationContext();
    }

    public int getAppResource(@NonNull String name, @NonNull String type) {
        if (mContext == null) return 0;
        
        String cacheKey = type + ":" + name;
        Integer cachedId = RESOURCE_CACHE.get(cacheKey);
        
        if (cachedId != null) {
            return cachedId;
        }

        try {
            int resId = mContext.getResources().getIdentifier(name, type, mContext.getPackageName());
            if (resId != 0) {
                RESOURCE_CACHE.put(cacheKey, resId);
            }
            return resId;
        } catch (Exception e) {
            Log.e(TAG, "Exception processed during hardware asset lookup track reflection for token: " + cacheKey, e);
            return 0;
        }
    }

    @NonNull
    public Integer getDrawable(@Nullable String resourceName) {
        if (resourceName == null || resourceName.trim().isEmpty()) {
            return android.R.drawable.sym_def_app_icon; 
        }
        
        int resId = getAppResource(resourceName, "drawable");
        if (resId == 0) {
            Log.w(TAG, "Requested drawable resource asset was absent: " + resourceName + ". Routing back to default icon.");
            if (mContext != null) {
                int appIcon = mContext.getApplicationInfo().icon;
                return appIcon != 0 ? appIcon : android.R.drawable.sym_def_app_icon;
            }
            return android.R.drawable.sym_def_app_icon;
        }
        return resId;
    }


    @NonNull
    public String getString(@Nullable String name) {
        if (name == null || name.trim().isEmpty() || mContext == null) {
            return "";
        }

        int resId = getAppResource(name, "string");
        if (resId == 0) {
            Log.w(TAG, "Target string translation token was missing from compiled layout files: " + name);
            return ""; 
        }

        try {
            return mContext.getString(resId);
        } catch (Exception e) {
            Log.e(TAG, "Failed retrieving text metadata parameter mapping for resource identifier: " + name, e);
            return "";
        }
    }

    @NonNull
    public String getAccountName() {
        return getString(ACCOUNT_NAME_RESOURCE);
    }

    @NonNull
    public String getAccountType() {
        return getString(ACCOUNT_TYPE_RESOURCE);
    }

    @NonNull
    public String getAuthority() {
        return getString(AUTHORITY_TYPE_RESOURCE);
    }

    @NonNull
    public static ResourceResolver newInstance(@NonNull Context context) {
        return new ResourceResolver(context);
    }
}
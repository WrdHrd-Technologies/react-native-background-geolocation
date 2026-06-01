package com.marianhello.bgloc.service;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.Setting;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.SettingDAO;

public class LocationServiceInfoImpl implements LocationServiceInfo {
    private static final String TAG = "LocationServiceInfoImpl";
    private final Context mContext;

    public LocationServiceInfoImpl(Context context) {
        mContext = context.getApplicationContext(); 
    }

    @Override
    public boolean isStarted() {
        if (LocationServiceImpl.isRunning()) {
            return true;
        }

        try {
            SettingDAO settingDao = DAOFactory.createSettingDAO(mContext);
            Setting setting = settingDao.retrieveSetting();
            if (setting != null) {
                return setting.isStarted();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to inspect persistent start status flag safely", e);
        }
        return false;
    }

    @Override
    public boolean isBound() {
        return LocationServiceImpl.isServiceBoundToClient();
    }
}
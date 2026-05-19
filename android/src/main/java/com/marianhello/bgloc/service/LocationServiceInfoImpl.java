package com.marianhello.bgloc.service;

import android.app.ActivityManager;
import android.content.Context;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.Setting;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.SettingDAO;

import org.json.JSONException;

public class LocationServiceInfoImpl implements LocationServiceInfo {
    private Context mContext;

    public LocationServiceInfoImpl(Context context) {
        mContext = context;
    }

    @Override
    public boolean isStarted() {
        SettingDAO settingDao = DAOFactory.createSettingDAO(mContext);

        try {
            Setting setting = settingDao.retrieveSetting();
            if(setting != null) {
                return setting.isStarted();
            }
        } catch (JSONException ignored) {

        }
        //ActivityManager.RunningServiceInfo info = getRunningServiceInfo();
        //if (info != null) {
        //return info.started;
        //}
        return false;
    }

    @Override
    public boolean isBound() {
        ActivityManager.RunningServiceInfo info = getRunningServiceInfo();
        if (info != null) {
            return info.clientCount > 0;
        }
        return false;
    }

    public ActivityManager.RunningServiceInfo getRunningServiceInfo() {
        String serviceName = LocationServiceImpl.class.getName();
        ActivityManager manager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceName.equals(info.service.getClassName())) {
                return info;
            }
        }
        return null;
    }
}

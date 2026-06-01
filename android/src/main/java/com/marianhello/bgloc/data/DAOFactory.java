package com.marianhello.bgloc.data;

import android.content.Context;
import androidx.annotation.NonNull;

import com.marianhello.bgloc.data.provider.ContentProviderLocationDAO;
import com.marianhello.bgloc.data.sqlite.SQLiteConfigurationDAO;
import com.marianhello.bgloc.data.sqlite.SQLiteSettingDAO;

public final class DAOFactory {

    private static volatile LocationDAO sLocationDAO;
    private static volatile ConfigurationDAO sConfigurationDAO;
    private static volatile SettingDAO sSettingDAO;

    private DAOFactory() {
        throw new UnsupportedOperationException("Factory infrastructure layer cannot be instantiated.");
    }

    @NonNull
    public static LocationDAO createLocationDAO(@NonNull Context context) {
        if (sLocationDAO == null) {
            synchronized (DAOFactory.class) {
                if (sLocationDAO == null) {
                    sLocationDAO = new ContentProviderLocationDAO(context.getApplicationContext());
                }
            }
        }
        return sLocationDAO;
    }

    @NonNull
    public static ConfigurationDAO createConfigurationDAO(@NonNull Context context) {
        if (sConfigurationDAO == null) {
            synchronized (DAOFactory.class) {
                if (sConfigurationDAO == null) {
                    sConfigurationDAO = new SQLiteConfigurationDAO(context.getApplicationContext());
                }
            }
        }
        return sConfigurationDAO;
    }


    @NonNull
    public static SettingDAO createSettingDAO(@NonNull Context context) {
        if (sSettingDAO == null) {
            synchronized (DAOFactory.class) {
                if (sSettingDAO == null) {
                    sSettingDAO = new SQLiteSettingDAO(context.getApplicationContext());
                }
            }
        }
        return sSettingDAO;
    }
}
package com.marianhello.bgloc.react;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.marianhello.bgloc.Setting;


public final class SettingMapper {

    private SettingMapper() {
        throw new UnsupportedOperationException("Bridge state transformation layers cannot be instantiated.");
    }


    @NonNull
    public static Setting fromMap(@Nullable ReadableMap options) {
        Setting setting = new Setting();
        if (options == null) return setting;

        if (options.hasKey("start")) {
            setting.setStarted(options.getBoolean("start"));
        }
        
        if (options.hasKey("updatedAt")) {
            setting.setUpdatedAt((long) options.getDouble("updatedAt"));
        }
        
        return setting;
    }

    @NonNull
    public static WritableMap toMap(@Nullable Setting setting) {
        WritableMap out = Arguments.createMap();
        if (setting == null) return out;

        if (setting.hasStart()) {
            out.putBoolean("start", setting.isStarted());
        }
        
        if (setting.hasUpdatedAt()) {
            out.putDouble("updatedAt", (double) setting.getUpdatedAt());
        }

        return out;
    }
}
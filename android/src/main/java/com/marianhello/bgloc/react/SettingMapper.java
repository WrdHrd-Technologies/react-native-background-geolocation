package com.marianhello.bgloc.react;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.marianhello.bgloc.Setting;

import org.json.JSONException;

public class SettingMapper {
    public static Setting fromMap(ReadableMap options) throws JSONException {
        Setting setting = new Setting();
        if (options.hasKey("start")) setting.setStarted(options.getBoolean("start"));
        if (options.hasKey("updatedAt")) setting.setUpdatedAt(options.getInt("updatedAt"));
        return setting;
    }

    public static ReadableMap toMap(Setting setting) {
        WritableMap out = Arguments.createMap();
        if (setting.hasStart()) {
            out.putBoolean("start", setting.isStarted());
        }
        if (setting.hasUpdatedAt()) {
            out.putInt("updatedAt", setting.getUpdatedAt());
        }

        return out;
    }
}

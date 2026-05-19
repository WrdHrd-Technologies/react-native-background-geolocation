package com.marianhello.bgloc.data;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.Setting;

import org.json.JSONException;

public interface SettingDAO {
    boolean persistSetting(Setting setting) throws NullPointerException;
    Setting retrieveSetting() throws JSONException;
}
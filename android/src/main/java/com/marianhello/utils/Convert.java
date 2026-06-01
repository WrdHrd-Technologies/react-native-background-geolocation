package com.marianhello.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class Convert {

    private Convert() {
        throw new UnsupportedOperationException("Type conversion utility layer cannot be instantiated.");
    }

    public static int safeLongToInt(long l) {
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Structural overflow: Value [" + l + 
                    "] exceeds maximum signed 32-bit capacity parameters [" + Integer.MAX_VALUE + "].");
        }
        return (int) l;
    }

    @NonNull
    public static Map<String, Object> toMap(@Nullable JSONObject jsonObject) throws JSONException {
        if (jsonObject == null) {
            return new HashMap<>(0);
        }

        Map<String, Object> map = new HashMap<>(jsonObject.length());
        Iterator<String> keys = jsonObject.keys();
        
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = jsonObject.get(key);
            
            if (value == null || value == JSONObject.NULL) {
                map.put(key, null);
                continue;
            }
            
            if (value instanceof JSONArray) {
                value = toList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            
            map.put(key, value);
        }
        
        return map;
    }

    @NonNull
    public static List<Object> toList(@Nullable JSONArray array) throws JSONException {
        if (array == null) {
            return new ArrayList<>(0);
        }

        int totalLength = array.length();
        List<Object> list = new ArrayList<>(totalLength);
        
        for (int i = 0; i < totalLength; i++) {
            Object value = array.get(i);
            
            if (value == null || value == JSONObject.NULL) {
                list.add(null);
                continue;
            }
            
            if (value instanceof JSONArray) {
                value = toList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            
            list.add(value);
        }
        
        return list;
    }
}
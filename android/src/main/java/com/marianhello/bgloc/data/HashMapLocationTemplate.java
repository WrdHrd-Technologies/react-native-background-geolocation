package com.marianhello.bgloc.data;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HashMapLocationTemplate extends AbstractLocationTemplate
        implements Serializable {

    private static final String TAG = "HashMapLocTemplate";
    private static final long serialVersionUID = 1234L;

    private final Map<String, Object> mMap;

    public HashMapLocationTemplate(HashMapLocationTemplate template) {
        mMap = new HashMap<>();

        if (template == null || template.mMap == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : template.mMap.entrySet()) {
            mMap.put(
                    entry.getKey(),
                    deepCopy(entry.getValue())
            );
        }
    }

    public HashMapLocationTemplate(Map<?, ?> map) {
        mMap = new HashMap<>();

        if (map == null) {
            return;
        }

        for (Map.Entry<?, ?> entry : map.entrySet()) {

            if (entry.getKey() == null) {
                continue;
            }

            mMap.put(
                    String.valueOf(entry.getKey()),
                    deepCopy(entry.getValue())
            );
        }
    }

    @Override
    public Object locationToJson(
            BackgroundLocation location
    ) throws JSONException {

        if (location == null) {
            return new JSONObject();
        }

        return LocationMapper.withMap(
                mMap,
                location
        );
    }

    public Iterator<Map.Entry<String, Object>> iterator() {
        return mMap.entrySet().iterator();
    }

    public boolean containsKey(String key) {
        return key != null && mMap.containsKey(key);
    }

    public Object get(String key) {
        return key == null ? null : mMap.get(key);
    }

    @Override
    public boolean isEmpty() {
        return mMap.isEmpty();
    }

    @Override
    public boolean equals(Object obj) {

        if (this == obj) {
            return true;
        }

        if (!(obj instanceof HashMapLocationTemplate)) {
            return false;
        }

        HashMapLocationTemplate other =
                (HashMapLocationTemplate) obj;

        return mMap.equals(other.mMap);
    }

    @Override
    public int hashCode() {
        return mMap.hashCode();
    }

    @Override
    public String toString() {

        try {
            return new JSONObject(mMap).toString();
        } catch (Exception e) {
            Log.e(
                    TAG,
                    "Failed to serialize template.",
                    e
            );
            return "{}";
        }
    }

    public Map<String, Object> toMap() {
        return Collections.unmodifiableMap(mMap);
    }

    @Override
    public LocationTemplate clone() {
        return new HashMapLocationTemplate(this);
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object value) {

        if (value == null) {
            return null;
        }

        if (value instanceof Map) {

            Map<String, Object> copy =
                    new HashMap<>();

            for (Map.Entry<?, ?> entry :
                    ((Map<?, ?>) value).entrySet()) {

                if (entry.getKey() != null) {
                    copy.put(
                            String.valueOf(entry.getKey()),
                            deepCopy(entry.getValue())
                    );
                }
            }

            return copy;
        }

        if (value instanceof List) {

            List<Object> copy =
                    new ArrayList<>();

            for (Object item : (List<?>) value) {
                copy.add(deepCopy(item));
            }

            return copy;
        }

        return value;
    }
}
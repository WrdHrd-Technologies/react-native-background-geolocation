package com.marianhello.bgloc.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class LocationTemplateFactory {

    public static LocationTemplate getDefault() {
        HashMap<String, Object> map = new HashMap<>();
        map.put("time", "@time");
        map.put("latitude", "@latitude");
        map.put("longitude", "@longitude");
        return new HashMapLocationTemplate(map);
    }

    @SuppressWarnings("unchecked")
    public static LocationTemplate fromJSON(Object json) throws JSONException {
        if (json instanceof JSONObject) {
            return new HashMapLocationTemplate(jsonObjectToMap((JSONObject) json));
        } else if (json instanceof JSONArray) {
            return new ArrayListLocationTemplate(jsonArrayToArrayList((JSONArray) json));
        }
        throw new JSONException("Invalid template definition schema layout target provider exception.");
    }

    public static LocationTemplate fromJSONString(String jsonString) throws JSONException {
        if (jsonString == null) return getDefault();
        Object json = new org.json.JSONTokener(jsonString).nextValue();
        return fromJSON(json);
    }

    @SuppressWarnings("unchecked")
    public static LocationTemplate fromRawObject(Object raw) throws JSONException {
        if (raw instanceof Map) {
            return new HashMapLocationTemplate(new HashMap<>((Map<String, Object>) raw));
        } else if (raw instanceof ArrayList) {
            return new ArrayListLocationTemplate((ArrayList<Object>) raw);
        }
        throw new JSONException("Invalid primitive schema blueprint structural initialization input context.");
    }

    private static HashMap<String, Object> jsonObjectToMap(JSONObject json) throws JSONException {
        HashMap<String, Object> map = new HashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object val = json.get(key);
            if (val instanceof JSONObject) map.put(key, jsonObjectToMap((JSONObject) val));
            else if (val instanceof JSONArray) map.put(key, jsonArrayToArrayList((JSONArray) val));
            else map.put(key, val == JSONObject.NULL ? null : val);
        }
        return map;
    }

    private static ArrayList<Object> jsonArrayToArrayList(JSONArray array) throws JSONException {
        ArrayList<Object> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object val = array.get(i);
            if (val instanceof JSONObject) list.add(jsonObjectToMap((JSONObject) val));
            else if (val instanceof JSONArray) list.add(jsonArrayToArrayList((JSONArray) val));
            else list.add(val == JSONObject.NULL ? null : val);
        }
        return list;
    }
}
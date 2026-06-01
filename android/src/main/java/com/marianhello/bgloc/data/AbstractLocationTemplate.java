package com.marianhello.bgloc.data;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public abstract class AbstractLocationTemplate
        implements LocationTemplate, Serializable {

    private static final String TAG = "AbstractLocationTemplate";

    public static final String BUNDLE_KEY = "template";

    @Override
    public abstract LocationTemplate clone();

    public static final class LocationMapper {

        private LocationMapper() {
            throw new UnsupportedOperationException(
                    "Utility class cannot be instantiated"
            );
        }

        public static JSONObject withMap(
                Map<?, ?> values,
                BackgroundLocation location
        ) throws JSONException {

            JSONObject result = new JSONObject();

            if (values == null) {
                return result;
            }

            for (Map.Entry<?, ?> entry : values.entrySet()) {

                if (entry.getKey() == null) {
                    continue;
                }

                String key = String.valueOf(entry.getKey());

                try {
                    result.put(
                            key,
                            mapValue(entry.getValue(), location)
                    );
                } catch (Exception e) {

                    Log.e(
                            TAG,
                            "Failed mapping template key: " + key,
                            e
                    );

                    result.put(key, JSONObject.NULL);
                }
            }

            return result;
        }

        public static JSONArray withList(
                List<?> values,
                BackgroundLocation location
        ) throws JSONException {

            JSONArray result = new JSONArray();

            if (values == null) {
                return result;
            }

            for (Object value : values) {
                result.put(
                        mapValue(value, location)
                );
            }

            return result;
        }

        private static Object mapValue(
                Object value,
                BackgroundLocation location
        ) throws JSONException {

            if (value == null) {
                return JSONObject.NULL;
            }

            if (value instanceof String) {

                String stringValue = (String) value;

                if (location != null) {

                    Object locationValue =
                            location.getValueForKey(stringValue);

                    if (locationValue != null) {
                        return locationValue;
                    }
                }

                return stringValue;
            }

            if (value instanceof Map) {

                JSONObject object = new JSONObject();

                for (Map.Entry<?, ?> entry :
                        ((Map<?, ?>) value).entrySet()) {

                    if (entry.getKey() == null) {
                        continue;
                    }

                    object.put(
                            String.valueOf(entry.getKey()),
                            mapValue(
                                    entry.getValue(),
                                    location
                            )
                    );
                }

                return object;
            }

            if (value instanceof List) {

                JSONArray array = new JSONArray();

                for (Object item : (List<?>) value) {

                    array.put(
                            mapValue(
                                    item,
                                    location
                            )
                    );
                }

                return array;
            }

            if (value instanceof JSONObject) {

                JSONObject source = (JSONObject) value;
                JSONObject destination = new JSONObject();

                Iterator<String> keys = source.keys();

                while (keys.hasNext()) {

                    String key = keys.next();

                    destination.put(
                            key,
                            mapValue(
                                    source.get(key),
                                    location
                            )
                    );
                }

                return destination;
            }

            if (value instanceof JSONArray) {

                JSONArray source = (JSONArray) value;
                JSONArray destination = new JSONArray();

                for (int i = 0; i < source.length(); i++) {

                    destination.put(
                            mapValue(
                                    source.get(i),
                                    location
                            )
                    );
                }

                return destination;
            }

            return value;
        }
    }
}
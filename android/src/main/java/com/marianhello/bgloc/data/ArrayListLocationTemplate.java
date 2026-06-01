package com.marianhello.bgloc.data;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ArrayListLocationTemplate
        extends AbstractLocationTemplate
        implements Serializable {

    private static final String TAG = "ArrayListLocTemplate";
    private static final long serialVersionUID = 1234L;

    private final List<Object> mList;

    public ArrayListLocationTemplate(
            ArrayListLocationTemplate template
    ) {
        this.mList = new ArrayList<>();

        if (template == null || template.mList == null) {
            return;
        }

        for (Object item : template.mList) {
            this.mList.add(deepCopy(item));
        }
    }

    public ArrayListLocationTemplate(
            List<?> list
    ) {
        this.mList = new ArrayList<>();

        if (list == null) {
            return;
        }

        for (Object item : list) {
            this.mList.add(deepCopy(item));
        }
    }

    @Override
    public Object locationToJson(
            BackgroundLocation location
    ) throws JSONException {

        if (location == null) {
            return new JSONArray();
        }

        return LocationMapper.withList(
                mList,
                location
        );
    }

    public Iterator<Object> iterator() {
        return mList.iterator();
    }

    public boolean containsKey(String key) {
        if (key == null) {
            return false;
        }

        return mList.contains(key);
    }

    @Override
    public boolean isEmpty() {
        return mList.isEmpty();
    }

    @Override
    public boolean equals(Object obj) {

        if (this == obj) {
            return true;
        }

        if (!(obj instanceof ArrayListLocationTemplate)) {
            return false;
        }

        ArrayListLocationTemplate other =
                (ArrayListLocationTemplate) obj;

        return mList.equals(other.mList);
    }

    @Override
    public int hashCode() {
        return mList.hashCode();
    }

    @Override
    public String toString() {

        try {
            return new JSONArray(mList).toString();
        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Failed to serialize template",
                    e
            );

            return "[]";
        }
    }

    public Object[] toArray() {
        return mList.toArray();
    }

    public List<Object> toList() {
        return Collections.unmodifiableList(mList);
    }

    @Override
    public LocationTemplate clone() {
        return new ArrayListLocationTemplate(this);
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(
            Object value
    ) {

        if (value == null) {
            return null;
        }

        if (value instanceof Map) {

            Map<Object, Object> copy =
                    new LinkedHashMap<>();

            for (Map.Entry<?, ?> entry :
                    ((Map<?, ?>) value).entrySet()) {

                copy.put(
                        entry.getKey(),
                        deepCopy(entry.getValue())
                );
            }

            return copy;
        }

        if (value instanceof List) {

            List<Object> copy =
                    new ArrayList<>();

            for (Object item : (List<?>) value) {
                copy.add(
                        deepCopy(item)
                );
            }

            return copy;
        }

        return value;
    }
}
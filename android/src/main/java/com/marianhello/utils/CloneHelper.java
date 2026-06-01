

package com.marianhello.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

public final class CloneHelper {

    private CloneHelper() {
        throw new UnsupportedOperationException("Utility isolation helper layers cannot be instantiated.");
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> deepCopy(@Nullable Map<K, V> original) {
        if (original == null) {
            return null;
        }

        Map<K, V> copy = new HashMap<>(original.size());

        for (Map.Entry<K, V> entry : original.entrySet()) {
            K key = entry.getKey();
            V val = entry.getValue();

            if (val == null) {
                copy.put(key, null);
                continue;
            }

            if (val instanceof Map) {
                copy.put(key, (V) deepCopy((Map<?, ?>) val));
            } else if (val instanceof Cloneable) {
                try {
                    copy.put(key, (V) val.getClass().getMethod("clone").invoke(val));
                } catch (Exception e) {
                    copy.put(key, val);
                }
            } else {
                copy.put(key, val);
            }
        }

        return copy;
    }


    @Nullable
    public static <K, V> HashMap<K, V> deepCopyHashMap(@Nullable HashMap<K, V> original) {
        Map<K, V> isolatedMap = deepCopy(original);
        if (isolatedMap == null) return null;
        return new HashMap<>(isolatedMap);
    }
}
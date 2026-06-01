package ru.andremoniy.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Iterator;


public final class TextUtils {
    private static final String TAG = "TextUtils";

    private TextUtils() {
        throw new UnsupportedOperationException("Utility infrastructure class cannot be instantiated.");
    }

    @NonNull
    public static String join(@Nullable CharSequence delimiter, @Nullable Object[] tokens) {
        if (tokens == null || tokens.length == 0) {
            return "";
        }

        CharSequence safeDelimiter = (delimiter != null) ? delimiter : "";

        int estimatedSize = (tokens.length * 16) + (tokens.length * safeDelimiter.length());
        StringBuilder sb = new StringBuilder(estimatedSize);

        boolean firstTime = true;
        for (Object token : tokens) {
            if (token == null) continue;

            if (firstTime) {
                firstTime = false;
            } else {
                sb.append(safeDelimiter);
            }
            sb.append(token);
        }
        return sb.toString();
    }

    @NonNull
    public static String join(@Nullable CharSequence delimiter, @Nullable Iterable<?> tokens) {
        if (tokens == null) {
            return "";
        }

        Iterator<?> it = tokens.iterator();
        if (!it.hasNext()) {
            return "";
        }

        CharSequence safeDelimiter = (delimiter != null) ? delimiter : "";
        
        StringBuilder sb = new StringBuilder(256);

        boolean firstTime = true;
        while (it.hasNext()) {
            Object token = it.next();
            if (token == null) continue;

            if (firstTime) {
                firstTime = false;
            } else {
                sb.append(safeDelimiter);
            }
            sb.append(token);
        }
        return sb.toString();
    }
}
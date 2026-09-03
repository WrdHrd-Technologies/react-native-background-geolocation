package com.marianhello.bgloc.sync;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.JsonWriter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.marianhello.bgloc.ResourceResolver;
import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.LocationTemplate;
import com.marianhello.bgloc.data.LocationTemplateFactory;
import com.marianhello.bgloc.data.provider.LocationContentProvider;
import com.marianhello.bgloc.data.sqlite.SQLiteLocationContract.LocationEntry;
import com.marianhello.logging.LoggerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class BatchManager {

    private final Context context;
    private final org.slf4j.Logger logger;

    public BatchManager(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.logger = LoggerManager.getLogger(BatchManager.class);
    }

    @NonNull
    private Uri getLocationContentUri() {
        ResourceResolver resolver = ResourceResolver.newInstance(context);
        return LocationContentProvider.getContentUri(resolver.getAuthority());
    }

    /**
     * Creates a chunked batch file matching the given sync threshold and chunk size limits.
     */
    @Nullable
    public File createBatch(
            @NonNull Long batchStartMillis,
            int syncThreshold,
            int maxLocations,
            @Nullable LocationTemplate template
    ) throws IOException {

        LocationTemplate effectiveTemplate = (template != null)
                ? template
                : LocationTemplateFactory.getDefault();

        ContentResolver resolver = context.getContentResolver();
        Uri contentUri = getLocationContentUri();

        String selection = LocationEntry.COLUMN_NAME_STATUS + " = ? AND (" +
                LocationEntry.COLUMN_NAME_BATCH_START_MILLIS + " IS NULL OR " +
                LocationEntry.COLUMN_NAME_BATCH_START_MILLIS + " < ?)";

        String[] selectionArgs = {
                String.valueOf(BackgroundLocation.SYNC_PENDING),
                String.valueOf(batchStartMillis)
        };

        String sortOrder = LocationEntry.COLUMN_NAME_TIME + " ASC";
        if (maxLocations > 0) {
            sortOrder += " LIMIT " + maxLocations;
        }

        List<Long> ids = new ArrayList<>();
        File batchFile = null;

        try (Cursor cursor = resolver.query(contentUri, null, selection, selectionArgs, sortOrder)) {
            if (cursor == null) {
                return null;
            }

            int count = cursor.getCount();
            if (count == 0 || (syncThreshold > 0 && count < syncThreshold)) {
                logger.debug("Skipping sync. Pending locations={} threshold={}", count, syncThreshold);
                return null;
            }

            // 1. Collect row IDs for batch reservation
            int idIndex = cursor.getColumnIndexOrThrow(LocationEntry._ID);
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(idIndex));
            }

            if (ids.isEmpty()) {
                return null;
            }

            // 2. Atomically assign batch ID to prevent double-processing
            String updateSelection = LocationEntry._ID + " IN (" + TextUtils.join(",", ids) + ")";
            ContentValues values = new ContentValues();
            values.put(LocationEntry.COLUMN_NAME_BATCH_START_MILLIS, batchStartMillis);
            resolver.update(contentUri, values, updateSelection, null);

            // 3. Serialize records to temp JSON batch file
            File batchesDir = new File(context.getCacheDir(), "bgloc_batches");
            if (!batchesDir.exists() && !batchesDir.mkdirs()) {
                throw new IOException("Failed creating batch directory: " + batchesDir.getAbsolutePath());
            }

            batchFile = new File(batchesDir, "batch_" + batchStartMillis + ".json");

            try (FileOutputStream fos = new FileOutputStream(batchFile);
                 BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
                 JsonWriter writer = new JsonWriter(bw)) {

                writer.beginArray();
                cursor.moveToPosition(-1);

                while (cursor.moveToNext()) {
                    BackgroundLocation location = BackgroundLocation.fromCursor(cursor);
                    if (location == null) continue;

                    Object payload = effectiveTemplate.locationToJson(location);
                    JsonWriterUtil.write(writer, payload);
                }

                writer.endArray();
                writer.flush();
            }

            logger.info("Created batch {} with {} locations", batchFile.getName(), ids.size());
            return batchFile;

        } catch (Exception e) {
            if (batchFile != null && batchFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                batchFile.delete();
            }

            if (!ids.isEmpty()) {
                unassignBatch(batchStartMillis);
            }

            throw new IOException("Failed to create location batch", e);
        }
    }

    @Nullable
    public File createBatch(
            @NonNull Long batchStartMillis,
            int syncThreshold,
            @Nullable LocationTemplate template
    ) throws IOException {
        return createBatch(batchStartMillis, syncThreshold, 50, template);
    }

    @Nullable
    public File createBatch(
            @NonNull Long batchStartMillis,
            int syncThreshold
    ) throws IOException {
        return createBatch(batchStartMillis, syncThreshold, 50, null);
    }

    /**
     * Marks all rows associated with the given batch ID as DELETED.
     */
    public void setBatchCompleted(@NonNull Long batchId) {
        ContentResolver resolver = context.getContentResolver();
        Uri contentUri = getLocationContentUri();

        String selection = LocationEntry.COLUMN_NAME_BATCH_START_MILLIS + " = ?";
        String[] selectionArgs = { String.valueOf(batchId) };

        ContentValues values = new ContentValues();
        values.put(LocationEntry.COLUMN_NAME_STATUS, BackgroundLocation.DELETED);

        resolver.update(contentUri, values, selection, selectionArgs);
    }

    /**
     * Releases locked rows back to unassigned state on failure or worker cancellation.
     */
    public void unassignBatch(@NonNull Long batchId) {
        ContentResolver resolver = context.getContentResolver();
        Uri contentUri = getLocationContentUri();

        String selection = LocationEntry.COLUMN_NAME_BATCH_START_MILLIS + " = ?";
        String[] selectionArgs = { String.valueOf(batchId) };

        ContentValues values = new ContentValues();
        values.putNull(LocationEntry.COLUMN_NAME_BATCH_START_MILLIS);

        resolver.update(contentUri, values, selection, selectionArgs);
        logger.debug("Reset batch assignment for batchId: {}", batchId);
    }

    private static final class JsonWriterUtil {

        private JsonWriterUtil() {
            throw new AssertionError("No instances");
        }

        static void write(@NonNull JsonWriter writer, @Nullable Object value) throws IOException {
            if (value == null || value == JSONObject.NULL) {
                writer.nullValue();
                return;
            }

            if (value instanceof String) {
                writer.value((String) value);
                return;
            }

            if (value instanceof Number) {
                writer.value((Number) value);
                return;
            }

            if (value instanceof Boolean) {
                writer.value((Boolean) value);
                return;
            }

            if (value instanceof JSONObject) {
                writeObject(writer, (JSONObject) value);
                return;
            }

            if (value instanceof JSONArray) {
                writeArray(writer, (JSONArray) value);
                return;
            }

            writer.value(String.valueOf(value));
        }

        private static void writeObject(@NonNull JsonWriter writer, @NonNull JSONObject object) throws IOException {
            writer.beginObject();
            Iterator<String> keys = object.keys();

            while (keys.hasNext()) {
                String key = keys.next();
                writer.name(key);
                try {
                    write(writer, object.get(key));
                } catch (JSONException e) {
                    throw new IOException("Failed to read JSONObject key: " + key, e);
                }
            }

            writer.endObject();
        }

        private static void writeArray(@NonNull JsonWriter writer, @NonNull JSONArray array) throws IOException {
            writer.beginArray();
            try {
                for (int i = 0; i < array.length(); i++) {
                    write(writer, array.get(i));
                }
            } catch (JSONException e) {
                throw new IOException("Failed to read JSONArray index", e);
            }
            writer.endArray();
        }
    }
}
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
        ResourceResolver resolver =
                ResourceResolver.newInstance(context);

        return LocationContentProvider.getContentUri(
                resolver.getAuthority()
        );
    }

    @Nullable
    public File createBatch(
            @NonNull Long batchStartMillis,
            int syncThreshold,
            @Nullable LocationTemplate template
    ) throws IOException {

        LocationTemplate effectiveTemplate =
                template != null
                        ? template
                        : LocationTemplateFactory.getDefault();

        ContentResolver resolver =
                context.getContentResolver();

        Uri contentUri =
                getLocationContentUri();

        String selection =
                LocationEntry.COLUMN_NAME_STATUS + " = ? AND (" +
                        LocationEntry.COLUMN_NAME_BATCH_START_MILLIS +
                        " IS NULL OR " +
                        LocationEntry.COLUMN_NAME_BATCH_START_MILLIS +
                        " < ?)";

        String[] selectionArgs = {
                String.valueOf(BackgroundLocation.SYNC_PENDING),
                String.valueOf(batchStartMillis)
        };

        List<Long> ids = new ArrayList<>();
        File batchFile = null;

        try (Cursor cursor = resolver.query(
                contentUri,
                null,
                selection,
                selectionArgs,
                LocationEntry.COLUMN_NAME_TIME + " ASC"
        )) {

            if (cursor == null) {
                return null;
            }

            /*
             * 0 = sync immediately
             * >0 = wait until threshold reached
             */
            if (syncThreshold > 0 &&
                    cursor.getCount() < syncThreshold) {

                logger.debug(
                        "Skipping sync. Pending locations={} threshold={}",
                        cursor.getCount(),
                        syncThreshold
                );

                return null;
            }

            batchFile = File.createTempFile(
                    "locations_batch_",
                    ".json",
                    context.getCacheDir()
            );

            try (
                    FileOutputStream fos =
                            new FileOutputStream(batchFile);

                    BufferedWriter bw =
                            new BufferedWriter(
                                    new OutputStreamWriter(
                                            fos,
                                            StandardCharsets.UTF_8
                                    )
                            );

                    JsonWriter writer =
                            new JsonWriter(bw)
            ) {

                writer.beginArray();

                int idIndex =
                        cursor.getColumnIndexOrThrow(
                                LocationEntry._ID
                        );

                while (cursor.moveToNext()) {

                    long rowId =
                            cursor.getLong(idIndex);

                    ids.add(rowId);

                    BackgroundLocation location =
                            BackgroundLocation.fromCursor(cursor);

                    Object payload =
                            effectiveTemplate.locationToJson(location);

                    JsonWriterUtil.write(writer, payload);
                }

                writer.endArray();
                writer.flush();
            }

            if (!ids.isEmpty()) {

                String updateSelection =
                        LocationEntry._ID +
                                " IN (" +
                                TextUtils.join(",", ids) +
                                ")";

                ContentValues values =
                        new ContentValues();

                values.put(
                        LocationEntry.COLUMN_NAME_BATCH_START_MILLIS,
                        batchStartMillis
                );

                resolver.update(
                        contentUri,
                        values,
                        updateSelection,
                        null
                );
            }

            logger.info(
                    "Created batch {} with {} locations",
                    batchFile.getName(),
                    ids.size()
            );

            return batchFile;

        } catch (Exception e) {

            if (batchFile != null &&
                    batchFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                batchFile.delete();
            }

            throw new IOException(
                    "Failed to create location batch",
                    e
            );
        }
    }

    @Nullable
    public File createBatch(
            @NonNull Long batchStartMillis,
            int syncThreshold
    ) throws IOException {

        return createBatch(
                batchStartMillis,
                syncThreshold,
                null
        );
    }

    public void setBatchCompleted(
            @NonNull Long batchId
    ) {

        ContentResolver resolver =
                context.getContentResolver();

        Uri contentUri =
                getLocationContentUri();

        String selection =
                LocationEntry.COLUMN_NAME_BATCH_START_MILLIS +
                        " = ?";

        String[] selectionArgs = {
                String.valueOf(batchId)
        };

        ContentValues values =
                new ContentValues();

        values.put(
                LocationEntry.COLUMN_NAME_STATUS,
                BackgroundLocation.DELETED
        );

        resolver.update(
                contentUri,
                values,
                selection,
                selectionArgs
        );
    }

    private static final class JsonWriterUtil {

        private JsonWriterUtil() {
            throw new AssertionError("No instances");
        }

        static void write(
                @NonNull JsonWriter writer,
                @Nullable Object value
        ) throws IOException {

            if (value == null ||
                    value == JSONObject.NULL) {

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

        private static void writeObject(
                @NonNull JsonWriter writer,
                @NonNull JSONObject object
        ) throws IOException {

            writer.beginObject();

            Iterator<String> keys = object.keys();

            while (keys.hasNext()) {

                String key = keys.next();

                writer.name(key);

                try {
                    write(writer, object.get(key));
                } catch (JSONException e) {
                    throw new IOException(
                            "Failed to read JSONObject",
                            e
                    );
                }
            }

            writer.endObject();
        }

        private static void writeArray(
                @NonNull JsonWriter writer,
                @NonNull JSONArray array
        ) throws IOException {

            writer.beginArray();

            try {

                for (int i = 0; i < array.length(); i++) {
                    write(writer, array.get(i));
                }

            } catch (JSONException e) {

                throw new IOException(
                        "Failed to read JSONArray",
                        e
                );
            }

            writer.endArray();
        }
    }
}
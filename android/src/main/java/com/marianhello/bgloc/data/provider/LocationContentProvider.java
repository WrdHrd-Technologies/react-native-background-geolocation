package com.marianhello.bgloc.data.provider;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Build;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.marianhello.bgloc.ResourceResolver;
import com.marianhello.bgloc.data.sqlite.SQLiteLocationContract.LocationEntry;
import com.marianhello.bgloc.data.sqlite.SQLiteOpenHelper;

public class LocationContentProvider extends ContentProvider {
    private static final String TAG = "LocationContentProvider";

    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    private static final int ALL_ITEMS = 10;
    private static final int ONE_ITEM = 20;

    private static volatile boolean sIsInitialized = false;
    private SQLiteOpenHelper mDatabaseHelper;

    private static synchronized void ensureInitialized(String authority) {
        if (sIsInitialized) return;

        sUriMatcher.addURI(authority, LocationEntry.TABLE_NAME, ALL_ITEMS);
        sUriMatcher.addURI(authority, LocationEntry.TABLE_NAME + "/#", ONE_ITEM);
        sIsInitialized = true;
    }

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;

        Context appContext = context.getApplicationContext();

        // Direct Boot Check: Defer initialization if device is in Direct Boot locked state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager userManager = (UserManager) appContext.getSystemService(Context.USER_SERVICE);
            if (userManager != null && !userManager.isUserUnlocked()) {
                Log.w(TAG, "Device is Direct Boot locked. LocationContentProvider storage init deferred.");
                return true;
            }
        }

        try {
            ResourceResolver resourceResolver = ResourceResolver.newInstance(appContext);
            ensureInitialized(resourceResolver.getAuthority());
            mDatabaseHelper = SQLiteOpenHelper.getHelper(appContext);
        } catch (Exception e) {
            Log.e(TAG, "Failed initializing database helper during provider startup.", e);
        }

        return true;
    }

    private synchronized SQLiteOpenHelper getDatabaseHelper() {
        if (mDatabaseHelper == null) {
            Context context = getContext();
            if (context != null) {
                Context appContext = context.getApplicationContext();
                ResourceResolver resourceResolver = ResourceResolver.newInstance(appContext);
                ensureInitialized(resourceResolver.getAuthority());
                mDatabaseHelper = SQLiteOpenHelper.getHelper(appContext);
            }
        }
        return mDatabaseHelper;
    }

    @Override
    public String getType(@NonNull Uri uri) {
        switch (sUriMatcher.match(uri)) {
            case ALL_ITEMS:
                return "vnd.android.cursor.dir/vnd." + LocationEntry.TABLE_NAME;
            case ONE_ITEM:
                return "vnd.android.cursor.item/vnd." + LocationEntry.TABLE_NAME;
            default:
                throw new IllegalArgumentException("Unknown operational URI path: " + uri);
        }
    }

    @Override
    public Cursor query(
            @NonNull Uri uri,
            @Nullable String[] projection,
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder) {

        SQLiteOpenHelper helper = getDatabaseHelper();
        if (helper == null) return null;

        int uriType = sUriMatcher.match(uri);
        SQLiteDatabase db = helper.getReadableDatabase();
        SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
        queryBuilder.setTables(LocationEntry.TABLE_NAME);

        switch (uriType) {
            case ALL_ITEMS:
                if (TextUtils.isEmpty(sortOrder)) {
                    sortOrder = LocationEntry.COLUMN_NAME_TIME + " ASC";
                }
                break;

            case ONE_ITEM:
                queryBuilder.appendWhere(LocationEntry._ID + "=" + uri.getLastPathSegment());
                break;

            default:
                throw new IllegalArgumentException("Unsupported query URI target: " + uri);
        }

        Cursor cursor = queryBuilder.query(db, projection, selection, selectionArgs, null, null, sortOrder);

        Context context = getContext();
        if (context != null && cursor != null) {
            cursor.setNotificationUri(context.getContentResolver(), uri);
        }
        return cursor;
    }

    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        SQLiteOpenHelper helper = getDatabaseHelper();
        if (helper == null) return null;

        int uriType = sUriMatcher.match(uri);
        if (uriType != ALL_ITEMS) {
            throw new IllegalArgumentException("Unsupported write operation destination: " + uri);
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        long id = db.insert(LocationEntry.TABLE_NAME, null, values);

        if (id > 0) {
            Uri itemUri = ContentUris.withAppendedId(uri, id);
            notifyAllListeners(itemUri);
            return itemUri;
        }

        throw new SQLException("Failed to write coordinate payload into storage layer for URI: " + uri);
    }

    @Override
    public int bulkInsert(@NonNull Uri uri, @NonNull ContentValues[] valuesArray) {
        SQLiteOpenHelper helper = getDatabaseHelper();
        if (helper == null) return 0;

        int uriType = sUriMatcher.match(uri);
        if (uriType != ALL_ITEMS) {
            throw new IllegalArgumentException("Unsupported bulk write destination: " + uri);
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransactionNonExclusive();
        int rowsInserted = 0;

        try {
            for (ContentValues values : valuesArray) {
                if (values == null) continue;
                long id = db.insert(LocationEntry.TABLE_NAME, null, values);
                if (id > 0) {
                    rowsInserted++;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        if (rowsInserted > 0) {
            notifyAllListeners(uri);
        }
        return rowsInserted;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        SQLiteOpenHelper helper = getDatabaseHelper();
        if (helper == null) return 0;

        int uriType = sUriMatcher.match(uri);
        int rowsDeleted;
        SQLiteDatabase db = helper.getWritableDatabase();

        switch (uriType) {
            case ALL_ITEMS:
                rowsDeleted = db.delete(LocationEntry.TABLE_NAME, selection, selectionArgs);
                break;

            case ONE_ITEM:
                String finalSelection = LocationEntry._ID + " = ?";
                selectionArgs = appendSelectionArgument(selectionArgs, uri.getLastPathSegment());
                if (!TextUtils.isEmpty(selection)) {
                    finalSelection += " AND (" + selection + ")";
                }
                rowsDeleted = db.delete(LocationEntry.TABLE_NAME, finalSelection, selectionArgs);
                break;

            default:
                throw new IllegalArgumentException("Unsupported delete target URI: " + uri);
        }

        if (rowsDeleted > 0) {
            notifyAllListeners(uri);
        }
        return rowsDeleted;
    }

    @Override
    public int update(
            @NonNull Uri uri,
            @Nullable ContentValues values,
            @Nullable String selection,
            @Nullable String[] selectionArgs) {

        SQLiteOpenHelper helper = getDatabaseHelper();
        if (helper == null) return 0;

        int uriType = sUriMatcher.match(uri);
        int rowsUpdated;
        SQLiteDatabase db = helper.getWritableDatabase();

        switch (uriType) {
            case ALL_ITEMS:
                rowsUpdated = db.update(LocationEntry.TABLE_NAME, values, selection, selectionArgs);
                break;

            case ONE_ITEM:
                String finalSelection = LocationEntry._ID + " = ?";
                selectionArgs = appendSelectionArgument(selectionArgs, uri.getLastPathSegment());
                if (!TextUtils.isEmpty(selection)) {
                    finalSelection += " AND (" + selection + ")";
                }
                rowsUpdated = db.update(LocationEntry.TABLE_NAME, values, finalSelection, selectionArgs);
                break;

            default:
                throw new IllegalArgumentException("Unsupported update target URI: " + uri);
        }

        if (rowsUpdated > 0) {
            notifyAllListeners(uri);
        }
        return rowsUpdated;
    }

    private String[] appendSelectionArgument(String[] currentArgs, String newArg) {
        if (currentArgs == null) {
            return new String[]{newArg};
        }
        String[] newArgs = new String[currentArgs.length + 1];
        System.arraycopy(currentArgs, 0, newArgs, 0, currentArgs.length);
        newArgs[currentArgs.length] = newArg;
        return newArgs;
    }

    private void notifyAllListeners(Uri uri) {
        Context context = getContext();
        if (context != null) {
            ContentResolver resolver = context.getContentResolver();
            if (resolver != null) {
                resolver.notifyChange(uri, null);
            }
        }
    }

    public static Uri getBaseContentUri(String authority) {
        return Uri.parse("content://" + authority);
    }

    public static Uri getContentUri(String authority) {
        return getBaseContentUri(authority).buildUpon()
                .appendPath(LocationEntry.TABLE_NAME)
                .build();
    }

    public static Uri buildUriWithId(String authority, long id) {
        return getContentUri(authority).buildUpon()
                .appendPath(Long.toString(id))
                .build();
    }
}
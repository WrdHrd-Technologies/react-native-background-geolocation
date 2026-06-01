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
import android.text.TextUtils;
import android.util.Log;

import com.marianhello.bgloc.ResourceResolver;
import com.marianhello.bgloc.data.sqlite.SQLiteLocationContract.LocationEntry;
import com.marianhello.bgloc.data.sqlite.SQLiteOpenHelper;


public class LocationContentProvider extends ContentProvider {
    private static final String TAG = "LocationContentProvider";

    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    private static final int ALL_ITEMS = 10;
    private static final int ONE_ITEM = 20;

    private static boolean sIsInitialized = false;
    private final Object mInitializationLock = new Object();
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

        ResourceResolver resourceResolver = ResourceResolver.newInstance(context.getApplicationContext());
        ensureInitialized(resourceResolver.getAuthority());

        mDatabaseHelper = SQLiteOpenHelper.getHelper(context.getApplicationContext());

        try {
            SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();
            if (db != null && !db.isReadOnly()) {
                db.enableWriteAheadLogging();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed forcing background WAL pool parameters on ContentProvider setup boundary.", e);
        }

        return true;
    }

    @Override
    public String getType(Uri uri) {
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
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {

        int uriType = sUriMatcher.match(uri);
        SQLiteDatabase db = mDatabaseHelper.getReadableDatabase();
        SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
        queryBuilder.setTables(LocationEntry.TABLE_NAME);

        switch (uriType) {
            case ALL_ITEMS:
                if (TextUtils.isEmpty(sortOrder)) {
                    sortOrder = LocationEntry.COLUMN_NAME_TIME + " ASC";
                }
                break;

            case ONE_ITEM:
                queryBuilder.appendWhere("(" + LocationEntry._ID + " = ?)");
                selectionArgs = appendSelectionArgument(selectionArgs, uri.getLastPathSegment());
                break;

            default:
                throw new IllegalArgumentException("Unsupported query URI target: " + uri);
        }

        Cursor cursor = queryBuilder.query(db, projection, selection, selectionArgs, null, null, sortOrder);

        Context context = getContext();
        if (context != null) {
            cursor.setNotificationUri(context.getContentResolver(), uri);
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        int uriType = sUriMatcher.match(uri);
        if (uriType != ALL_ITEMS) {
            throw new IllegalArgumentException("Unsupported write operation destination: " + uri);
        }

        SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();
        long id = db.insert(LocationEntry.TABLE_NAME, null, values);

        if (id > 0) {
            Uri itemUri = ContentUris.withAppendedId(uri, id);
            notifyAllListeners(itemUri);
            return itemUri;
        }

        throw new SQLException("Failed to write coordinate payload into storage layer for URI: " + uri);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int uriType = sUriMatcher.match(uri);
        int rowsDeleted;
        SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();

        switch (uriType) {
            case ALL_ITEMS:
                rowsDeleted = db.delete(LocationEntry.TABLE_NAME, selection, selectionArgs);
                break;

            case ONE_ITEM:
                String finalSelection = LocationEntry._ID + " = ?";
                if (!TextUtils.isEmpty(selection)) {
                    finalSelection += " AND (" + selection + ")";
                }
                selectionArgs = appendSelectionArgument(selectionArgs, uri.getLastPathSegment());
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
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int uriType = sUriMatcher.match(uri);
        int rowsUpdated;
        SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();

        switch (uriType) {
            case ALL_ITEMS:
                rowsUpdated = db.update(LocationEntry.TABLE_NAME, values, selection, selectionArgs);
                break;

            case ONE_ITEM:
                String finalSelection = LocationEntry._ID + " = ?";
                if (!TextUtils.isEmpty(selection)) {
                    finalSelection += " AND (" + selection + ")";
                }
                selectionArgs = appendSelectionArgument(selectionArgs, uri.getLastPathSegment());
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
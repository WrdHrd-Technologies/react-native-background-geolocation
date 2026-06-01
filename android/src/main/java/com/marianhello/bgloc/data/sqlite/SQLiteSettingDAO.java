package com.marianhello.bgloc.data.sqlite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.marianhello.bgloc.Setting;
import com.marianhello.bgloc.data.SettingDAO;
import com.marianhello.bgloc.data.sqlite.SQLiteSettingContract.SettingEntry;

public class SQLiteSettingDAO implements SettingDAO {
    private static final String TAG = "SQLiteSettingDAO";
    private final SQLiteDatabase db;

    public SQLiteSettingDAO(Context context) {
        SQLiteOpenHelper helper = SQLiteOpenHelper.getHelper(context.getApplicationContext());
        this.db = helper.getWritableDatabase();
    }

    public SQLiteSettingDAO(SQLiteDatabase db) {
        this.db = db;
    }

    private static final String[] COLUMNS = {
            SettingEntry._ID,               
            SettingEntry.COLUMN_NAME_START,        
            SettingEntry.COLUMN_NAME_UPDATED_AT   
    };

    @Override
    public Setting retrieveSetting() {
        Setting setting = null;

        try (Cursor cursor = db.query(
                SettingEntry.TABLE_NAME,
                COLUMNS,
                null,
                null,
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                setting = hydrate(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "Fatal error encountered during tracking state profile retrieval.", e);
        }

        return setting;
    }

    @Override
    public boolean persistSetting(Setting setting) {
        if (setting == null) return false;

        try {
            ContentValues values = getContentValues(setting);
            long rowId = db.replace(SettingEntry.TABLE_NAME, SettingEntry.COLUMN_NAME_NULLABLE, values);
            Log.d(TAG, "Tracking engine operational state persisted under instance row ID: " + rowId);
            return rowId > -1;
        } catch (Exception e) {
            Log.e(TAG, "Settings write transaction rejected by underlying database engine.", e);
            return false;
        }
    }

    private Setting hydrate(Cursor c) {
        Setting setting = Setting.getDefault();
        
        setting.setStarted(c.getInt(1) == 1);
        
        setting.setUpdatedAt(c.getLong(2));
        
        return setting;
    }

    private ContentValues getContentValues(Setting setting) {
        ContentValues values = new ContentValues();
        values.put(SettingEntry._ID, 1);
        values.put(SettingEntry.COLUMN_NAME_START, setting.isStarted() ? 1 : 0);
        values.put(SettingEntry.COLUMN_NAME_UPDATED_AT, setting.getUpdatedAt());
        return values;
    }
}
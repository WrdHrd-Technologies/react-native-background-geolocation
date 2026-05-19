package com.marianhello.bgloc.data.sqlite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import com.marianhello.bgloc.Setting;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.SettingDAO;

import org.json.JSONException;

public class SQLiteSettingDAO implements SettingDAO {
    private static final String TAG = SQLiteSettingDAO.class.getName();

    private SQLiteDatabase db;

    public SQLiteSettingDAO(Context context) {
        SQLiteOpenHelper helper = SQLiteOpenHelper.getHelper(context);
        this.db = helper.getWritableDatabase();
    }

    public SQLiteSettingDAO(SQLiteDatabase db) {
        this.db = db;
    }

    public Setting retrieveSetting() throws JSONException {
        Cursor cursor = null;

        String[] columns = {
                SQLiteSettingContract.SettingEntry._ID,
                SQLiteSettingContract.SettingEntry.COLUMN_NAME_START,
                SQLiteSettingContract.SettingEntry.COLUMN_NAME_UPDATED_AT,
        };

        String whereClause = null;
        String[] whereArgs = null;
        String groupBy = null;
        String having = null;
        String orderBy = null;

        Setting setting = null;
        try {
            cursor = db.query(
                    SQLiteSettingContract.SettingEntry.TABLE_NAME,  // The table to query
                    columns,                   // The columns to return
                    whereClause,               // The columns for the WHERE clause
                    whereArgs,                 // The values for the WHERE clause
                    groupBy,                   // don't group the rows
                    having,                    // don't filter by row groups
                    orderBy                    // The sort order
            );
            if (cursor.moveToFirst()) {
                setting = hydrate(cursor);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return setting;
    }

    public boolean persistSetting(Setting setting) throws NullPointerException {
        long rowId = db.replace(SQLiteSettingContract.SettingEntry.TABLE_NAME, SQLiteSettingContract.SettingEntry.COLUMN_NAME_NULLABLE, getContentValues(setting));
        Log.d(TAG, "Configuration persisted with rowId = " + rowId);
        if (rowId > -1) {
            return true;
        } else {
            return false;
        }
    }

    private Setting hydrate(Cursor c) throws JSONException {
        Setting setting = Setting.getDefault();
        setting.setStarted(c.getInt(c.getColumnIndex(SQLiteSettingContract.SettingEntry.COLUMN_NAME_START)) == 1);
        setting.setUpdatedAt(c.getInt(c.getColumnIndex(SQLiteSettingContract.SettingEntry.COLUMN_NAME_UPDATED_AT)));
        return setting;
    }

    private ContentValues getContentValues(Setting setting) throws NullPointerException {
        ContentValues values = new ContentValues();
        values.put(SQLiteSettingContract.SettingEntry._ID, 1);
        values.put(SQLiteSettingContract.SettingEntry.COLUMN_NAME_START,  (setting.isStarted() == true) ? 1 : 0);
        values.put(SQLiteSettingContract.SettingEntry.COLUMN_NAME_UPDATED_AT, setting.getUpdatedAt());
        return values;
    }
}

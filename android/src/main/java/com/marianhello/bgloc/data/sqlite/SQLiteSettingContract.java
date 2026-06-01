package com.marianhello.bgloc.data.sqlite;

import android.provider.BaseColumns;

import static com.marianhello.bgloc.data.sqlite.SQLiteOpenHelper.COMMA_SEP;
import static com.marianhello.bgloc.data.sqlite.SQLiteOpenHelper.INTEGER_TYPE;

public final class SQLiteSettingContract {
    
    private SQLiteSettingContract() {
        throw new UnsupportedOperationException("Contract utility schema definitions cannot be instantiated.");
    }

    public static abstract class SettingEntry implements BaseColumns {
        public static final String TABLE_NAME = "Setting";
        public static final String COLUMN_NAME_NULLABLE = "NULLHACK";
        public static final String COLUMN_NAME_START = "start";
        public static final String COLUMN_NAME_UPDATED_AT = "updated_at";

        public static final String SQL_CREATE_SETTING_TABLE =
                "CREATE TABLE " + SettingEntry.TABLE_NAME + " (" +
                        SettingEntry._ID + " INTEGER PRIMARY KEY," +
                        SettingEntry.COLUMN_NAME_START + " " + INTEGER_TYPE + COMMA_SEP +
                        SettingEntry.COLUMN_NAME_UPDATED_AT + " " + INTEGER_TYPE +
                        " )";

        public static final String SQL_DROP_SETTING_TABLE =
                "DROP TABLE IF EXISTS " + SettingEntry.TABLE_NAME;
    }
}
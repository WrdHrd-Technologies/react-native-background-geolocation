package com.marianhello.bgloc.data.sqlite;

import static com.marianhello.bgloc.data.sqlite.SQLiteOpenHelper.COMMA_SEP;
import static com.marianhello.bgloc.data.sqlite.SQLiteOpenHelper.INTEGER_TYPE;

import android.provider.BaseColumns;

public class SQLiteSettingContract {
    // To prevent someone from accidentally instantiating the contract class,
    // give it an empty constructor.
    public SQLiteSettingContract() {}

    /* Inner class that defines the table contents */
    public static abstract class SettingEntry implements BaseColumns {
        public static final String TABLE_NAME = "Setting";
        public static final String COLUMN_NAME_NULLABLE = "NULLHACK";
        public static final String COLUMN_NAME_START = "start";
        public static final String COLUMN_NAME_UPDATED_AT = "updated_at";

        public static final String SQL_CREATE_SETTING_TABLE =
                "CREATE TABLE " + SQLiteSettingContract.SettingEntry.TABLE_NAME + " (" +
                        SQLiteSettingContract.SettingEntry._ID + " INTEGER PRIMARY KEY," +
                        SettingEntry.COLUMN_NAME_START + INTEGER_TYPE + COMMA_SEP +
                        SettingEntry.COLUMN_NAME_UPDATED_AT + INTEGER_TYPE +
                        " )";

        public static final String SQL_DROP_SETTING_TABLE =
                "DROP TABLE IF EXISTS " + SQLiteSettingContract.SettingEntry.TABLE_NAME;
    }
}

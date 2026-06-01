package com.marianhello.bgloc.service;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class LocationServiceIntentBuilder {

    private static final String KEY_MESSAGE = "msg";
    private static final String KEY_COMMAND = "cmd";

    private final Context mContext;
    private String mMessage;
    private Command mCommand;

    public static class Command {
        private static final String KEY_COMMAND_ID = "cmd_id";
        private static final String KEY_COMMAND_ARGUMENT = "cmd_arg";
        private static final String KEY_COMMAND_ARGUMENT_TYPE = "cmd_arg_type";
        private static final int ARGUMENT_TYPE_MISSING = 0;
        private static final int ARGUMENT_TYPE_STRING = 1;
        private static final int ARGUMENT_TYPE_PARCELABLE = 2;

        private final @CommandId int mCommandId;
        private Parcelable mParcelableArg;
        private String mStringArg;
        private int mArgType = 0;

        public Command(int id) {
            this.mCommandId = id;
        }

        public Command(int id, String argument) {
            mCommandId = id;
            mStringArg = argument;
            mArgType = ARGUMENT_TYPE_STRING;
        }

        public Command(int id, Parcelable argument) {
            mCommandId = id;
            mParcelableArg = argument;
            mArgType = ARGUMENT_TYPE_PARCELABLE;
        }

        public int getId() {
            return mCommandId;
        }

        public Object getArgument() {
            switch (mArgType) {
                case ARGUMENT_TYPE_STRING:
                    return mStringArg;
                case ARGUMENT_TYPE_PARCELABLE:
                    return mParcelableArg;
                default:
                    return null;
            }
        }

        public Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_COMMAND_ID, mCommandId);

            if (mStringArg != null) {
                bundle.putInt(KEY_COMMAND_ARGUMENT_TYPE, ARGUMENT_TYPE_STRING);
                bundle.putString(KEY_COMMAND_ARGUMENT, mStringArg);
            } else if (mParcelableArg != null) {
                bundle.putInt(KEY_COMMAND_ARGUMENT_TYPE, ARGUMENT_TYPE_PARCELABLE);
                bundle.putParcelable(KEY_COMMAND_ARGUMENT, mParcelableArg);
            } else {
                bundle.putInt(KEY_COMMAND_ARGUMENT_TYPE, ARGUMENT_TYPE_MISSING);
            }

            return bundle;
        }

        @SuppressWarnings("deprecation")
        public static Command from(Bundle bundle) {
            if (bundle == null) {
                return new Command(CommandId.INVALID);
            }

            @CommandId int commandId = bundle.getInt(KEY_COMMAND_ID, CommandId.INVALID);
            int argumentType = bundle.getInt(KEY_COMMAND_ARGUMENT_TYPE, ARGUMENT_TYPE_MISSING);

            if (argumentType == ARGUMENT_TYPE_STRING) {
                return new Command(commandId, bundle.getString(KEY_COMMAND_ARGUMENT));
            } else if (argumentType == ARGUMENT_TYPE_PARCELABLE) {
                Parcelable parcelable;
                // Type-safe unmarshaling for Android 13 (API 33) and newer
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    parcelable = bundle.getParcelable(KEY_COMMAND_ARGUMENT, Parcelable.class);
                } else {
                    // Backward-compatible fallback branch for older Android versions
                    parcelable = bundle.getParcelable(KEY_COMMAND_ARGUMENT);
                }
                return new Command(commandId, parcelable);
            }

            return new Command(commandId);
        }
    }

    public static LocationServiceIntentBuilder getInstance(Context context) {
        return new LocationServiceIntentBuilder(context);
    }

    public LocationServiceIntentBuilder(Context context) {
        mContext = context.getApplicationContext(); // Safeguard against short-lived Activity context leaks
    }

    public LocationServiceIntentBuilder setMessage(String message) {
        mMessage = message;
        return this;
    }

    public LocationServiceIntentBuilder setCommand(@CommandId int commandId) {
        if (commandId != CommandId.INVALID) {
            mCommand = new Command(commandId);
        }
        return this;
    }

    public LocationServiceIntentBuilder setCommand(@CommandId int commandId, String arg) {
        if (commandId != CommandId.INVALID) {
            mCommand = new Command(commandId, arg);
        }
        return this;
    }

    public LocationServiceIntentBuilder setCommand(@CommandId int commandId, Parcelable arg) {
        if (commandId != CommandId.INVALID) {
            mCommand = new Command(commandId, arg);
        }
        return this;
    }

    public Intent build() {
        if (mContext == null) {
            throw new IllegalStateException("Cannot construct intent tracking configuration: context target reference lost.");
        }
        Intent intent = new Intent(mContext, LocationServiceImpl.class);
        if (mCommand != null) {
            intent.putExtra(KEY_COMMAND, mCommand.toBundle());
        }
        if (mMessage != null) {
            intent.putExtra(KEY_MESSAGE, mMessage);
        }
        return intent;
    }

    public static boolean containsCommand(Intent intent) {
        return intent != null && intent.hasExtra(KEY_COMMAND);
    }

    public static boolean containsMessage(Intent intent) {
        return intent != null && intent.hasExtra(KEY_MESSAGE);
    }

    public static Command getCommand(Intent intent) {
        if (intent == null) return new Command(CommandId.INVALID);
        Bundle bundle = intent.getBundleExtra(KEY_COMMAND);
        return Command.from(bundle);
    }

    public static String getMessage(Intent intent) {
        if (intent == null) return null;
        return intent.getStringExtra(KEY_MESSAGE);
    }
}
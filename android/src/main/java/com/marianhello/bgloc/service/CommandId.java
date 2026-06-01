package com.marianhello.bgloc.service;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import androidx.annotation.IntDef;

@IntDef({
        CommandId.INVALID,
        CommandId.START,
        CommandId.START_FOREGROUND_SERVICE,
        CommandId.STOP,
        CommandId.STOP_FOREGROUND,
        CommandId.START_FOREGROUND,
        CommandId.CONFIGURE,
        CommandId.REGISTER_HEADLESS_TASK,
        CommandId.START_HEADLESS_TASK,
        CommandId.STOP_HEADLESS_TASK,
        CommandId.HEARTBEAT_PING
})
@Retention(RetentionPolicy.SOURCE)
public @interface CommandId {
    int INVALID = -1;
    int START = 0;
    int START_FOREGROUND_SERVICE = 1;
    int STOP = 2;
    int STOP_FOREGROUND = 3;
    int START_FOREGROUND = 4;
    int CONFIGURE = 5;
    int REGISTER_HEADLESS_TASK = 6;
    int START_HEADLESS_TASK = 7;
    int STOP_HEADLESS_TASK = 8;
    int HEARTBEAT_PING = 9;
}
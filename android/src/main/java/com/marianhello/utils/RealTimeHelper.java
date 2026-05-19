package com.marianhello.utils;
import android.app.Activity;
import android.content.Context;
import android.util.Log;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import ir.programmerplus.realtime.BuildConfig;
import ir.programmerplus.realtime.RealTime;


public class RealTimeHelper {
    private static final String TAG = "TrueTime";

    public static Date now() {
        try{
            Date date = RealTime.now();
            return date;
        }
        catch (Exception ignore){
           return new Date();
        }
    }

    public static void initialize(Context context)
    {
        RealTime.builder(context)
                .withGpsProvider()
//                .withNtpServer("time.nist.gov")
//                .withNtpServer("time.google.com")
//                .withNtpServer("time.windows.com")
//                .withTimeServer("https://bing.com")
                .withTimeServer("https://google.com")
                .setSyncBackoffDelay(2, TimeUnit.HOURS)
                .build(date -> Log.d(TAG, "RealTime is initialized, current dateTime: " + date));
    }
}

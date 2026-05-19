package com.marianhello.bgloc.data;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

public class BatteryInfo {
    private int batteryLevel = 0;
    private boolean isCharging = false;
    public BatteryInfo(Context context){
            IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, iFilter);
            int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
            int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
            double batteryPct = level / (double) scale;
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

            this.batteryLevel = (int) (batteryPct * 100);
            this.isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            
    }

    public int getBatteryLevel() {
       return this.batteryLevel;
    }
    public boolean getIsCharging() {
        return this.isCharging;
    }
}
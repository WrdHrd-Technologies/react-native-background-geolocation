package com.marianhello.bgloc.data;

import android.content.Context;
import android.os.BatteryManager;

public class BatteryUtils {
    // Static method to get battery percentage and charging status
    public static BatteryInfo getBatteryStatus(Context context) {
        BatteryManager batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);

        if (batteryManager == null) {
            // Return default values if battery status is unavailable
            return new BatteryInfo(-1, false);
        }

        // Get battery percentage
        int level = -1;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }

        // Check charging status
        int status = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
        }
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;


        // Return battery information as an object
        return new BatteryInfo(level, isCharging);
    }
    public static class BatteryInfo {
        private final int batteryPercentage; // Battery percentage (0-100)
        private final boolean isCharging;    // Whether the battery is charging

        public BatteryInfo(int batteryPercentage, boolean isCharging) {
            this.batteryPercentage = batteryPercentage;
            this.isCharging = isCharging;
        }

        public int getBatteryPercentage() {
            return batteryPercentage;
        }

        public boolean isCharging() {
            return isCharging;
        }

        @Override
        public String toString() {
            return "Battery Percentage: " + batteryPercentage + "%, Is Charging: " + isCharging;
        }
    }
}
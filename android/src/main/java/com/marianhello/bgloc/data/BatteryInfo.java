package com.marianhello.bgloc.data;

public final class BatteryInfo {
    private final int batteryLevel;
    private final boolean isCharging;

    public BatteryInfo(int batteryLevel, boolean isCharging) {
        this.batteryLevel = batteryLevel;
        this.isCharging = isCharging;
    }

    public int getBatteryLevel() {
        return this.batteryLevel;
    }

    public boolean getIsCharging() {
        return this.isCharging;
    }
}
package com.marianhello.bgloc.data;

import java.util.Objects;

public final class BatteryInfo {
    private final int batteryLevel;
    private final boolean isCharging;

    public static final BatteryInfo UNKNOWN = new BatteryInfo(-1, false);

    public BatteryInfo(int batteryLevel, boolean isCharging) {
        if (batteryLevel > 100) {
            this.batteryLevel = 100;
        } else if (batteryLevel < 0) {
            this.batteryLevel = -1; 
        } else {
            this.batteryLevel = batteryLevel;
        }
        this.isCharging = isCharging;
    }

    public int getBatteryLevel() {
        return this.batteryLevel;
    }

    /**
     * Preserves compatibility with existing getter call sites.
     */
    public boolean getIsCharging() {
        return this.isCharging;
    }

    /**
     * Idiomatic Java boolean getter.
     */
    public boolean isCharging() {
        return this.isCharging;
    }

    public boolean isValid() {
        return this.batteryLevel >= 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatteryInfo that = (BatteryInfo) o;
        return batteryLevel == that.batteryLevel && isCharging == that.isCharging;
    }

    @Override
    public int hashCode() {
        return Objects.hash(batteryLevel, isCharging);
    }

    @Override
    public String toString() {
        return "BatteryInfo{" +
                "batteryLevel=" + batteryLevel + "%" +
                ", isCharging=" + isCharging +
                '}';
    }
}
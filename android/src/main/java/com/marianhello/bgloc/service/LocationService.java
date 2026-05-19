package com.marianhello.bgloc.service;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.Setting;

public interface LocationService {
    void start();
    void startForegroundService();
    void stop();
    void startForeground();
    void stopForeground();
    void setting(Setting setting);
    void configure(Config config);
    void registerHeadlessTask(String jsFunction);
    void startHeadlessTask();
    void stopHeadlessTask();
    void executeProviderCommand(int command, int arg);
}

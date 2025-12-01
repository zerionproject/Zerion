package com.professor.zerion.android.api;

public interface ScreenFilterMonitor {
    interface AppDetails {
        String getPackageName();
        String getName();
    }

    AppDetails getInstalledScreenFilter();
    boolean isScreenFilterPresent();
    java.util.Collection<AppDetails> getApps();
    void allowApps(java.util.Collection<String> packageNames);
}
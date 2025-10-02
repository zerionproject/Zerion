package com.professor.zerion.android.api;

public interface LockManager {
    String ACTION_LOCK = "com.professor.zerion.android.LOCK";
    String EXTRA_PID = "pid";

    boolean isLocked();
    void setLocked(boolean locked);
    void checkIfLockable();
    void onActivityStart();
    void onActivityStop();
    androidx.lifecycle.LiveData<Boolean> isLockable();
}
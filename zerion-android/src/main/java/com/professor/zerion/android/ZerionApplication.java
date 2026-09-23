package com.professor.zerion.android;

import android.app.Activity;

import org.zerionproject.core.BrambleApplication;
import com.professor.zerion.android.navdrawer.NavDrawerActivity;

public interface ZerionApplication extends BrambleApplication {

	Class<? extends Activity> ENTRY_ACTIVITY = NavDrawerActivity.class;

	AndroidComponent getApplicationComponent();

	boolean isRunningInBackground();

	boolean isInstrumentationTest();
}

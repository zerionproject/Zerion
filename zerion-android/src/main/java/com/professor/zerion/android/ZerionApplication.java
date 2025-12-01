package com.professor.zerion.android;

import android.app.Activity;
import android.content.SharedPreferences;

import org.briarproject.bramble.BrambleApplication;
import com.professor.zerion.android.navdrawer.NavDrawerActivity;

public interface ZerionApplication extends BrambleApplication {

	Class<? extends Activity> ENTRY_ACTIVITY = NavDrawerActivity.class;

	AndroidComponent getApplicationComponent();

	SharedPreferences getDefaultSharedPreferences();

	boolean isRunningInBackground();

	boolean isInstrumentationTest();
}

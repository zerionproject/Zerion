package com.professor.zerion.android.logout;

import android.app.Activity;
import android.os.Bundle;

public class ExitActivity extends Activity {

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		com.professor.zerion.android.util.AppTasks.finishOtherTasks(this, true);
		finishAndRemoveTask();
		System.exit(0);
	}
}
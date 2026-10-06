package com.professor.zerion.android.logout;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

public class HideUiActivity extends Activity {

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		com.professor.zerion.android.util.AppTasks.finishOtherTasks(this, true);
		finishAndRemoveTask();
	}
}

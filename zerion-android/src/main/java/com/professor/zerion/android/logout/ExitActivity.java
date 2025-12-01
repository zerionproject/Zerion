package com.professor.zerion.android.logout;

import android.app.Activity;
import android.os.Bundle;


public class ExitActivity extends Activity {


	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		finishAndRemoveTask();
		System.exit(0);
	}
}
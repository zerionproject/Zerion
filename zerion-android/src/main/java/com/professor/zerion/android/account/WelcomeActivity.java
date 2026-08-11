package com.professor.zerion.android.account;

import android.os.Bundle;
import android.view.WindowManager;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BaseActivity;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class WelcomeActivity extends BaseActivity {

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	protected boolean forceScreenshotProtection() {
		return true;
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
				WindowManager.LayoutParams.FLAG_SECURE);
		setContentView(R.layout.activity_fragment_container);
		if (state == null) {
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.fragmentContainer, new WelcomeFragment())
					.commit();
		}
	}
}

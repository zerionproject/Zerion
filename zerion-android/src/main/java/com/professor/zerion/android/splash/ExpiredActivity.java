package com.professor.zerion.android.splash;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;

import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.Localizer;

import javax.inject.Inject;

import androidx.appcompat.app.AppCompatActivity;

import static android.content.Intent.ACTION_VIEW;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;

public class ExpiredActivity extends AppCompatActivity
		implements OnClickListener {

	@Inject
	@AppModule.UiPrefs
	SharedPreferences uiPrefs;

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);

		AppModule.getAndroidComponent(this).inject(this);

		boolean screenshotProtection = uiPrefs.getBoolean("pref_screenshot_protection", true);
		if (screenshotProtection) {
			getWindow().addFlags(FLAG_SECURE);
		} else {
			getWindow().clearFlags(FLAG_SECURE);
		}

		if (SDK_INT >= 31) getWindow().setHideOverlayWindows(true);

		setContentView(R.layout.activity_expired);
		findViewById(R.id.download_zerion_button).setOnClickListener(this);
	}

	@Override
	protected void attachBaseContext(Context base) {
		super.attachBaseContext(
				Localizer.getInstance().applyLocaleToContext(base));
	}

	@Override
	public void onClick(View v) {
		String url = getString(R.string.expired_redirect_url);
		Uri uri = Uri.parse(url);
		startActivity(new Intent(ACTION_VIEW, uri));
		finish();
	}
}

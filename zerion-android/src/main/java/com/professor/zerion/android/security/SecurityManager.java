package com.professor.zerion.android.security;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.view.WindowManager;

import com.professor.zerion.android.AppModule;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.briarproject.nullsafety.NotNullByDefault;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_SCREENSHOT_PROTECTION;

@Singleton
@NotNullByDefault
public final class SecurityManager {

	private final SharedPreferences uiPrefs;

	@Inject
	public SecurityManager(Application application,
			@AppModule.UiPrefs SharedPreferences uiPrefs) {
		this.uiPrefs = uiPrefs;
	}

	public void applyScreenshotProtection(Activity activity) {
		applyScreenshotProtection(activity, false);
	}

	public void applyScreenshotProtection(Activity activity, boolean force) {
		boolean enabled = force
				|| uiPrefs.getBoolean(PREF_SCREENSHOT_PROTECTION, true);
		if (enabled) {
			activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		} else {
			activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
		}
	}

	public boolean isScreenshotProtectionEnabled() {
		return uiPrefs.getBoolean(PREF_SCREENSHOT_PROTECTION, true);
	}

}

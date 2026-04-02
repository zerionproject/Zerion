package com.professor.zerion.android.settings;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import com.professor.zerion.android.AppModule;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class AppIconManager {

	public static final String PREF_APP_ICON = "pref_app_icon";

	public static final int ICON_DEFAULT = 0;
	public static final int ICON_CALCULATOR = 1;
	public static final int ICON_NOTES = 2;
	public static final int ICON_WEATHER = 3;

	private static final String[] ALIAS_NAMES = {
			"com.professor.zerion.android.splash.SplashScreenActivity",
			"com.professor.zerion.launcher.Calculator",
			"com.professor.zerion.launcher.Notes",
			"com.professor.zerion.launcher.Weather",
	};

	public static int getCurrentIcon(Context context) {
		SharedPreferences prefs = AppModule.getAndroidComponent(context)
				.securePreferences();
		return prefs.getInt(PREF_APP_ICON, ICON_DEFAULT);
	}

	public static void setAppIcon(Context context, int iconIndex) {
		if (iconIndex < 0 || iconIndex >= ALIAS_NAMES.length) return;

		PackageManager pm = context.getPackageManager();

		for (int i = 0; i < ALIAS_NAMES.length; i++) {
			ComponentName cn = new ComponentName(context, ALIAS_NAMES[i]);
			int newState = (i == iconIndex)
					? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
					: PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
			try {
				pm.setComponentEnabledSetting(cn, newState,
						PackageManager.DONT_KILL_APP);
			} catch (Exception ignored) {
			}
		}

		SharedPreferences prefs = AppModule.getAndroidComponent(context)
				.securePreferences();
		prefs.edit().putInt(PREF_APP_ICON, iconIndex).apply();
	}
}

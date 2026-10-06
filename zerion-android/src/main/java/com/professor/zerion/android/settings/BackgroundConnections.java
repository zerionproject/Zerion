package com.professor.zerion.android.settings;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

public final class BackgroundConnections {

	public static final String PREF_KEY = "background_connection_mode";

	public enum Mode {
		ALWAYS("always"),
		WHILE_OPEN("while_open"),
		PAUSED("paused");

		private final String value;

		Mode(String value) {
			this.value = value;
		}

		public String getValue() {
			return value;
		}

		public static Mode fromValue(@Nullable String value) {
			if (value != null) {
				for (Mode m : values()) {
					if (m.value.equals(value)) return m;
				}
			}
			return ALWAYS;
		}
	}

	private BackgroundConnections() {
	}

	public static Mode getMode(@Nullable SharedPreferences prefs) {
		if (prefs == null) return Mode.ALWAYS;
		return Mode.fromValue(prefs.getString(PREF_KEY, Mode.ALWAYS.getValue()));
	}

	public static void setMode(SharedPreferences prefs, Mode mode) {
		prefs.edit().putString(PREF_KEY, mode.getValue()).apply();
	}

	public static boolean shouldRun(Mode mode, boolean signedIn,
			boolean appInForeground) {
		if (!signedIn) return false;
		switch (mode) {
			case ALWAYS:
				return true;
			case PAUSED:
				return false;
			case WHILE_OPEN:
				return appInForeground;
			default:
				return true;
		}
	}

	public static boolean allowStart(Mode mode, boolean signedIn,
			boolean appInForeground) {
		return shouldRun(mode, signedIn, appInForeground);
	}

	public static boolean requireStop(Mode mode, boolean signedIn,
			boolean appInForeground) {
		return signedIn && !shouldRun(mode, signedIn, appInForeground);
	}
}

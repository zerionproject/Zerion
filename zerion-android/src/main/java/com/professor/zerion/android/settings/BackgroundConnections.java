package com.professor.zerion.android.settings;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * The user's Background Connections choice and the policy that decides, from
 * that choice plus the sign-in and app-foreground state, whether the Zerion
 * service and its networking (Tor and all P2P transports) should be running.
 *
 * The policy is pure and side-effect free so it can be unit tested; storage is
 * in encrypted preferences so the mode is readable before the database is open
 * and survives process death and reboot.
 */
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

	/**
	 * Whether the service and its networking should currently be running.
	 * Never true when signed out; PAUSED is always false; WHILE_OPEN follows
	 * the app-foreground state; ALWAYS keeps it running whenever signed in.
	 */
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

	/**
	 * Whether a start request should be honoured now. Distinct from
	 * {@link #shouldRun} only for clarity at call sites that gate startService.
	 */
	public static boolean allowStart(Mode mode, boolean signedIn,
			boolean appInForeground) {
		return shouldRun(mode, signedIn, appInForeground);
	}

	/**
	 * Whether the running service must be stopped now for the given state.
	 */
	public static boolean requireStop(Mode mode, boolean signedIn,
			boolean appInForeground) {
		return signedIn && !shouldRun(mode, signedIn, appInForeground);
	}
}

package com.professor.zerion.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.security.ZerionEncryptedPrefs;

import org.briarproject.nullsafety.NotNullByDefault;

import static android.content.Context.MODE_PRIVATE;

@NotNullByDefault
public final class EarlyPrefs {

	private static final String FILE = "early_ui_prefs";

	private EarlyPrefs() {
	}

	private static volatile SharedPreferences cached;

	public static SharedPreferences get(Context ctx) {
		SharedPreferences c = cached;
		if (c != null) return c;
		try {
			c = ZerionEncryptedPrefs.createBootReadable(ctx, FILE);
		} catch (Throwable fallback) {
			c = ctx.getSharedPreferences(FILE, MODE_PRIVATE);
		}
		cached = c;
		return c;
	}
}

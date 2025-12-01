package com.professor.zerion.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;

import static android.content.Context.MODE_PRIVATE;

@NotNullByDefault
public final class EarlyPrefs {

	private EarlyPrefs() {
	}

	public static SharedPreferences get(Context ctx) {
		return ctx.getSharedPreferences(ctx.getPackageName() + "_preferences", MODE_PRIVATE);
	}
}

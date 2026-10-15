package com.professor.zerion.android.security;

import android.annotation.TargetApi;
import android.app.Activity;

import org.briarproject.nullsafety.NotNullByDefault;

import static android.os.Build.VERSION.SDK_INT;

@NotNullByDefault
public final class RecentsSnapshots {

	private RecentsSnapshots() {
	}

	public static void disable(Activity activity) {
		disable(activity, SDK_INT);
	}

	@TargetApi(33)
	static void disable(Activity activity, int sdk) {
		if (sdk >= 33) activity.setRecentsScreenshotEnabled(false);
	}
}

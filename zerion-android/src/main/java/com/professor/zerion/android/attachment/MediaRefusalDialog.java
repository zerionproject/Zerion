package com.professor.zerion.android.attachment;

import android.app.Activity;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class MediaRefusalDialog {

	private MediaRefusalDialog() {
	}

	public static void show(Activity activity, MediaRefusedException e) {
		if (activity.isFinishing() || activity.isDestroyed()) return;
		new SecureAlertDialogBuilder(activity)
				.setTitle(R.string.media_refused_title)
				.setMessage(e.getUserMessage())
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}
}

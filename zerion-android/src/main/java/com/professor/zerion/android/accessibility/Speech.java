package com.professor.zerion.android.accessibility;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.core.view.ViewCompat;

@UiThread
@NotNullByDefault
public final class Speech {

	private Speech() {
	}

	public static boolean listening(View v) {
		Object am = v.getContext().getSystemService(
				Context.ACCESSIBILITY_SERVICE);
		return am instanceof AccessibilityManager
				&& ((AccessibilityManager) am).isEnabled();
	}

	@SuppressWarnings("deprecation")
	public static void say(View v, CharSequence text) {
		if (TextUtils.isEmpty(text) || !listening(v)) return;
		String spoken = text.toString();
		v.post(() -> v.announceForAccessibility(spoken));
	}

	public static void politely(View v) {
		ViewCompat.setAccessibilityLiveRegion(v,
				ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
	}

	public static boolean setText(TextView v, CharSequence text) {
		if (TextUtils.equals(v.getText(), text)) return false;
		v.setText(text);
		return true;
	}

	public static boolean setText(TextView v, @StringRes int text) {
		return setText(v, v.getContext().getText(text));
	}
}

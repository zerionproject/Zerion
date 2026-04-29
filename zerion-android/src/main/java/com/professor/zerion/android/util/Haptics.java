package com.professor.zerion.android.util;

import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.UiThread;

@UiThread
public final class Haptics {

	private Haptics() {
	}

	public static void tap(View v) {
		if (v == null) return;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				v.performHapticFeedback(
						HapticFeedbackConstants.CONTEXT_CLICK);
			} else {
				v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
			}
		} catch (Exception ignored) {
		}
	}

	public static void confirm(View v) {
		if (v == null) return;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
			} else {
				v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
			}
		} catch (Exception ignored) {
		}
	}

	public static void error(View v) {
		if (v == null) return;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				v.performHapticFeedback(HapticFeedbackConstants.REJECT);
			} else {
				v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
			}
		} catch (Exception ignored) {
		}
	}

	public static void notify(View v) {
		if (v == null) return;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				v.performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
			} else {
				v.performHapticFeedback(
						HapticFeedbackConstants.VIRTUAL_KEY);
			}
		} catch (Exception ignored) {
		}
	}
}

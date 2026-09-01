package com.professor.zerion.android.util;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Bounded-lifetime clipboard copies. A value copied here is marked sensitive
 * for the system, cleared automatically after its lifetime, and clearable
 * earlier (vault lock). A clear never removes a value that is provably not ours.
 *
 * <p>Android 10+ denies clipboard reads to an app that does not have window
 * focus, which is exactly the situation after the user switches away to paste.
 * Ownership is therefore proved in two ways: by the clip description's
 * timestamp recorded at copy time (readable without the clip content), or by
 * the content when it is readable. Only when neither can be read does a
 * sensitive copy whose lifetime has passed get cleared unconditionally; any
 * clear that could not run is retried when the app regains focus.
 */
@NotNullByDefault
public final class SecureClipboard {

	private static final long AUTO_CLEAR_MS = 60_000L;
	private static final Handler HANDLER =
			new Handler(Looper.getMainLooper());

	@androidx.annotation.Nullable
	private static volatile String lastCopied;
	private static volatile long lastTimestamp;
	private static volatile long clearDeadline;
	private static volatile boolean lastSensitive;

	private SecureClipboard() {
	}

	public static void copy(Context ctx, String label, String text) {
		copy(ctx, label, text, AUTO_CLEAR_MS, false);
	}

	/**
	 * Copy a sensitive value: EXTRA_IS_SENSITIVE (API 33+), automatic clear
	 * after {@code clearAfterMs}, earlier clear via {@link #clearIfOurs}. Only
	 * ever call on a direct user action after a warning.
	 */
	public static void copySensitive(Context ctx, String label, String text,
			long clearAfterMs) {
		copy(ctx, label, text, clearAfterMs, true);
	}

	private static void copy(Context ctx, String label, String text,
			long clearAfterMs, boolean sensitive) {
		ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
		if (cm == null) return;
		ClipData clip = ClipData.newPlainText(label, text);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			PersistableBundle extras = new PersistableBundle();
			extras.putBoolean(
					ClipDescription.EXTRA_IS_SENSITIVE, true);
			clip.getDescription().setExtras(extras);
		}
		cm.setPrimaryClip(clip);
		lastCopied = text;
		lastSensitive = sensitive;
		lastTimestamp = currentTimestamp(cm);
		clearDeadline = System.currentTimeMillis() + clearAfterMs;
		HANDLER.postDelayed(() -> clear(cm, text, sensitive), clearAfterMs);
	}

	/**
	 * Clear the clipboard now if it still holds the last value this class
	 * copied (for example on a vault lock). Safe to call from any thread.
	 */
	public static void clearIfOurs(Context ctx) {
		String ours = lastCopied;
		if (ours == null) return;
		ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
		if (cm == null) return;
		boolean sensitive = lastSensitive;
		if (Looper.myLooper() == Looper.getMainLooper()) {
			clear(cm, ours, sensitive);
		} else {
			HANDLER.post(() -> clear(cm, ours, sensitive));
		}
	}

	/**
	 * Call when the app regains focus: retries a sensitive clear whose lifetime
	 * has passed but which could not be completed while the app was in the
	 * background.
	 */
	public static void onAppFocused(Context ctx) {
		String ours = lastCopied;
		if (ours == null || !lastSensitive) return;
		if (System.currentTimeMillis() < clearDeadline) return;
		clearIfOurs(ctx);
	}

	private static void clear(ClipboardManager cm, String text,
			boolean sensitive) {
		try {
			Boolean stillOurs = holdsValue(cm, text);
			if (stillOurs != null && !stillOurs) {
				forget(text);
				return;
			}
			if (stillOurs == null && !sensitive) {
				return;
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				cm.clearPrimaryClip();
			} else {
				cm.setPrimaryClip(ClipData.newPlainText("", "​"));
			}
			forget(text);
		} catch (SecurityException ignored) {
		}
	}

	private static void forget(String text) {
		if (text.equals(lastCopied)) {
			lastCopied = null;
			lastTimestamp = 0;
		}
	}

	/**
	 * True/false when ownership can be decided; null when the clipboard cannot
	 * be read at all. The description timestamp (readable without the content)
	 * is checked first, then the content itself.
	 */
	@androidx.annotation.Nullable
	private static Boolean holdsValue(ClipboardManager cm, String text) {
		try {
			long ts = currentTimestamp(cm);
			long ours = lastTimestamp;
			if (ts > 0 && ours > 0) {
				return ts == ours;
			}
			if (!cm.hasPrimaryClip()) return null;
			ClipData current = cm.getPrimaryClip();
			if (current == null || current.getItemCount() == 0) return null;
			CharSequence currentText = current.getItemAt(0).getText();
			if (currentText == null) return null;
			return currentText.toString().equals(text);
		} catch (SecurityException e) {
			return null;
		}
	}

	private static long currentTimestamp(ClipboardManager cm) {
		try {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0;
			ClipDescription d = cm.getPrimaryClipDescription();
			return d == null ? 0 : d.getTimestamp();
		} catch (SecurityException e) {
			return 0;
		}
	}
}

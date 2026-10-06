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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

@NotNullByDefault
public final class SecureClipboard {

	private static final long AUTO_CLEAR_MS = 60_000L;
	private static final Handler HANDLER =
			new Handler(Looper.getMainLooper());
	private static final Object EXPIRY_TOKEN = new Object();

	@androidx.annotation.Nullable
	private static volatile byte[] lastCopiedDigest;
	private static volatile long lastTimestamp;
	private static volatile long clearDeadline;
	private static volatile boolean lastSensitive;
	private static volatile boolean clearPending;
	private static long generation;

	private SecureClipboard() {
	}

	public static void copy(Context ctx, String label, String text) {
		copy(ctx, label, text, AUTO_CLEAR_MS, false);
	}

	public static void copySensitive(Context ctx, String label, String text,
			long clearAfterMs) {
		copy(ctx, label, text, clearAfterMs, true);
	}

	private static synchronized void copy(Context ctx, String label,
			String text, long clearAfterMs, boolean sensitive) {
		ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
		if (cm == null) return;
		ClipData clip = ClipData.newPlainText(label, text);
		PersistableBundle extras = new PersistableBundle();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
		} else {
			extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
		}
		clip.getDescription().setExtras(extras);
		cm.setPrimaryClip(clip);
		wipe(lastCopiedDigest);
		lastCopiedDigest = digest(text);
		lastSensitive = sensitive;
		lastTimestamp = currentTimestamp(cm);
		clearPending = false;
		long copyGeneration = ++generation;
		HANDLER.removeCallbacksAndMessages(EXPIRY_TOKEN);
		if (clearAfterMs > 0) {
			clearDeadline = System.currentTimeMillis() + clearAfterMs;
			HANDLER.postDelayed(() -> expire(cm, copyGeneration),
					EXPIRY_TOKEN, clearAfterMs);
		} else {
			clearDeadline = Long.MAX_VALUE;
		}
	}

	public static void clearIfOurs(Context ctx) {
		if (lastCopiedDigest == null) return;
		ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
		if (cm == null) return;
		clear(cm);
	}

	public static void clearOnSignOut(Context ctx) {
		if (lastCopiedDigest == null) return;
		ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
		if (cm == null) return;
		clear(cm, true);
	}

	static boolean expiryQueued() {
		return HANDLER.hasMessages(0, EXPIRY_TOKEN);
	}

	public static void onAppFocused(Context ctx) {
		if (lastCopiedDigest == null) return;
		if (!clearPending && System.currentTimeMillis() < clearDeadline) {
			return;
		}
		clearIfOurs(ctx);
	}

	private static synchronized void expire(ClipboardManager cm,
			long copyGeneration) {
		if (copyGeneration != generation) return;
		clear(cm);
	}

	private static void clear(ClipboardManager cm) {
		clear(cm, false);
	}

	private static synchronized void clear(ClipboardManager cm,
			boolean unlessProvablyNotOurs) {
		byte[] digest = lastCopiedDigest;
		if (digest == null) return;
		try {
			Boolean stillOurs = holdsValue(cm, digest);
			if (stillOurs != null && !stillOurs) {
				forget();
				return;
			}
			if (stillOurs == null && !lastSensitive
					&& !unlessProvablyNotOurs) {
				clearPending = true;
				return;
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				cm.clearPrimaryClip();
			} else {
				cm.setPrimaryClip(ClipData.newPlainText("", "​"));
			}
			forget();
		} catch (SecurityException e) {
			clearPending = true;
		}
	}

	private static void forget() {
		HANDLER.removeCallbacksAndMessages(EXPIRY_TOKEN);
		wipe(lastCopiedDigest);
		lastCopiedDigest = null;
		lastTimestamp = 0;
		clearPending = false;
	}

	private static void wipe(@androidx.annotation.Nullable byte[] digest) {
		if (digest != null) Arrays.fill(digest, (byte) 0);
	}

	@androidx.annotation.Nullable
	private static Boolean holdsValue(ClipboardManager cm, byte[] digest) {
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
			return Arrays.equals(digest(currentText.toString()), digest);
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

	private static byte[] digest(String text) {
		try {
			return MessageDigest.getInstance("SHA-256")
					.digest(text.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}
}

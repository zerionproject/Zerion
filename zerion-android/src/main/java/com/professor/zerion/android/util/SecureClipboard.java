package com.professor.zerion.android.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.view.View;
import android.view.Window;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

@NotNullByDefault
public final class SecureClipboard {

	static final class Extras {
		static final String SENSITIVE = "android.content.extra.IS_SENSITIVE";
		static final String LIFETIME = "clip.lifetime_ms";
		static final String OWNER = "com.professor.zerion.clip";

		private Extras() {
		}
	}

	private static final long AUTO_CLEAR_MS = 60_000L;
	private static final Handler HANDLER =
			new Handler(Looper.getMainLooper());
	private static final Object EXPIRY_TOKEN = new Object();
	private static final Map<View, Boolean> WATCHED =
			Collections.synchronizedMap(new WeakHashMap<>());

	@androidx.annotation.Nullable
	private static volatile byte[] lastCopiedDigest;
	private static volatile long lastTimestamp;
	private static volatile long clearDeadline;
	private static volatile boolean clearPending;
	private static long generation;

	private SecureClipboard() {
	}

	public static void copy(Context ctx, String label, String text) {
		copySensitive(ctx, label, text, AUTO_CLEAR_MS);
	}

	public static synchronized void copySensitive(Context ctx, String label,
			String text, long clearAfterMs) {
		ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
		if (cm == null) return;
		ClipData clip = ClipData.newPlainText(label, text);
		PersistableBundle extras = new PersistableBundle();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
		} else {
			extras.putBoolean(Extras.SENSITIVE, true);
		}
		extras.putBoolean(Extras.OWNER, true);
		if (clearAfterMs > 0) extras.putLong(Extras.LIFETIME, clearAfterMs);
		clip.getDescription().setExtras(extras);
		cm.setPrimaryClip(clip);
		wipe(lastCopiedDigest);
		lastCopiedDigest = digest(text);
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
		clear(cm, false);
	}

	public static void clearOnSignOut(Context ctx) {
		clearIfOurs(ctx);
	}

	static boolean expiryQueued() {
		return HANDLER.hasMessages(0, EXPIRY_TOKEN);
	}

	public static void onAppFocused(Context ctx) {
		if (lastCopiedDigest == null) {
			ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
					Context.CLIPBOARD_SERVICE);
			if (cm != null) clearExpiredCopy(cm);
			return;
		}
		if (!clearPending && System.currentTimeMillis() < clearDeadline) {
			return;
		}
		ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
		if (cm != null) clear(cm, true);
	}

	public static void watchFocus(Activity activity) {
		Window window = activity.getWindow();
		View decor = window == null ? null : window.peekDecorView();
		if (decor == null || WATCHED.put(decor, Boolean.TRUE) != null) return;
		Context app = activity.getApplicationContext();
		decor.getViewTreeObserver().addOnWindowFocusChangeListener(
				hasFocus -> {
					if (hasFocus) onAppFocused(app);
				});
	}

	static synchronized void clearExpiredCopy(ClipboardManager cm) {
		if (lastCopiedDigest != null) return;
		try {
			ClipDescription d = cm.getPrimaryClipDescription();
			if (d == null) return;
			if (!marked(d)) return;
			long lifetime = d.getExtras().getLong(Extras.LIFETIME, 0);
			if (lifetime <= 0) return;
			long copiedAt = d.getTimestamp();
			long now = System.currentTimeMillis();
			if (copiedAt > 0 && copiedAt <= now && now - copiedAt < lifetime) {
				HANDLER.removeCallbacksAndMessages(EXPIRY_TOKEN);
				HANDLER.postDelayed(() -> clearExpiredCopy(cm), EXPIRY_TOKEN,
						lifetime - (now - copiedAt));
				return;
			}
			cm.clearPrimaryClip();
		} catch (SecurityException e) {
		}
	}

	private static synchronized void expire(ClipboardManager cm,
			long copyGeneration) {
		if (copyGeneration != generation) return;
		clear(cm, false);
	}

	private static synchronized void clear(ClipboardManager cm,
			boolean focused) {
		byte[] digest = lastCopiedDigest;
		if (digest == null) return;
		try {
			Boolean stillOurs = holdsOurCopy(cm, digest);
			if (stillOurs == null) {
				if (focused) {
					forget();
				} else {
					clearPending = true;
				}
				return;
			}
			if (stillOurs) cm.clearPrimaryClip();
			forget();
		} catch (SecurityException e) {
			clearPending = true;
		}
	}

	private static boolean marked(ClipDescription d) {
		PersistableBundle extras = d.getExtras();
		return extras != null && extras.getBoolean(Extras.SENSITIVE, false)
				&& extras.getBoolean(Extras.OWNER, false);
	}

	@androidx.annotation.VisibleForTesting
	static synchronized void forgetForTests() {
		forget();
	}

	@androidx.annotation.VisibleForTesting
	static synchronized void resetForTests() {
		forget();
		clearDeadline = 0;
		WATCHED.clear();
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
	private static Boolean holdsOurCopy(ClipboardManager cm, byte[] digest) {
		try {
			ClipDescription d = cm.getPrimaryClipDescription();
			if (d == null) return null;
			if (!marked(d)) return false;
			long ts = d.getTimestamp();
			long ours = lastTimestamp;
			if (ts > 0 && ours > 0) {
				return ts == ours;
			}
			ClipData current = cm.getPrimaryClip();
			if (current == null || current.getItemCount() == 0) return null;
			CharSequence currentText = current.getItemAt(0).getText();
			if (currentText == null) return false;
			return Arrays.equals(digest(currentText.toString()), digest);
		} catch (SecurityException e) {
			return null;
		}
	}

	private static long currentTimestamp(ClipboardManager cm) {
		try {
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

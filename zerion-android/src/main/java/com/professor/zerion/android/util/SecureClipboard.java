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

@NotNullByDefault
public final class SecureClipboard {

	private static final long AUTO_CLEAR_MS = 60_000L;
	private static final Handler HANDLER =
			new Handler(Looper.getMainLooper());

	private SecureClipboard() {
	}

	public static void copy(Context ctx, String label, String text) {
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
		HANDLER.postDelayed(() -> {
			try {
				if (!cm.hasPrimaryClip()) return;
				ClipData current = cm.getPrimaryClip();
				if (current == null || current.getItemCount() == 0) return;
				CharSequence currentText =
						current.getItemAt(0).getText();
				if (currentText == null) return;
				if (currentText.toString().equals(text)) {
					cm.setPrimaryClip(
							ClipData.newPlainText("", "​"));
				}
			} catch (SecurityException ignored) {
			}
		}, AUTO_CLEAR_MS);
	}
}

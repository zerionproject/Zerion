package com.professor.zerion.android.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class BrowserGuard {

	private BrowserGuard() {
	}

	public static void openUrl(Context ctx, String url) {
		new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.browser_warning_title)
				.setMessage(ctx.getString(R.string.browser_warning_message)
						+ "\n\n" + url)
				.setPositiveButton(R.string.browser_warning_open,
						(d, w) -> launchSystemBrowser(ctx, url))
				.setNeutralButton(R.string.browser_warning_copy,
						(d, w) -> copyToClipboard(ctx, url))
				.setNegativeButton(android.R.string.cancel, null)
				.setCancelable(true)
				.show();
	}

	public static void openUrl(Context ctx, Uri uri) {
		openUrl(ctx, uri.toString());
	}

	private static void launchSystemBrowser(Context ctx, String url) {
		try {
			Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
			i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			ctx.startActivity(i);
		} catch (RuntimeException ignored) {
		}
	}

	private static void copyToClipboard(Context ctx, String url) {
		ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
		if (cm != null) {
			cm.setPrimaryClip(ClipData.newPlainText("link", url));
			Toast.makeText(ctx, R.string.browser_warning_link_copied,
					Toast.LENGTH_SHORT).show();
		}
	}
}

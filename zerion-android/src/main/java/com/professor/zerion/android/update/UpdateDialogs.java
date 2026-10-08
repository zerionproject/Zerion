package com.professor.zerion.android.update;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class UpdateDialogs {

	private UpdateDialogs() {
	}

	public static void showAvailable(Context ctx, ReleaseAnnouncement a,
			String installedVersion) {
		String message = ctx.getString(R.string.updates_available_message,
				a.versionName, installedVersion, a.releasePageUrl, a.apkSha256);
		new SecureAlertDialogBuilder(ctx)
				.setTitle(R.string.updates_available_title)
				.setMessage(message)
				.setPositiveButton(R.string.updates_open,
						(d, w) -> open(ctx, a.releasePageUrl))
				.setNeutralButton(R.string.updates_copy_link,
						(d, w) -> copy(ctx, a.releasePageUrl))
				.setNegativeButton(R.string.updates_later, null)
				.show();
	}

	public static void showResult(Context ctx, UpdateChecker.Result r,
			String installedVersion, UpdateChecker checker) {
		if (r.outcome == UpdateChecker.Outcome.AVAILABLE
				&& r.announcement != null) {
			checker.popupShown(r.announcement);
			showAvailable(ctx, r.announcement, installedVersion);
			return;
		}
		String text;
		switch (r.outcome) {
			case UP_TO_DATE:
				text = ctx.getString(R.string.updates_up_to_date,
						installedVersion);
				break;
			case TOR_NOT_READY:
				text = ctx.getString(R.string.updates_tor_not_ready);
				break;
			case UNREACHABLE:
				text = ctx.getString(R.string.updates_unreachable);
				break;
			case STORE_INSTALL:
				String store = checker.storeName();
				text = ctx.getString(R.string.updates_store_install,
						store == null ? "" : store);
				break;
			default:
				text = ctx.getString(R.string.updates_not_verified);
		}
		new SecureAlertDialogBuilder(ctx)
				.setTitle(R.string.updates_check_title)
				.setMessage(text)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private static void open(Context ctx, String url) {
		try {
			Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
			i.addCategory(Intent.CATEGORY_BROWSABLE);
			ctx.startActivity(i);
		} catch (ActivityNotFoundException e) {
			Toast.makeText(ctx, R.string.updates_no_browser,
					Toast.LENGTH_LONG).show();
		}
	}

	private static void copy(Context ctx, String url) {
		ClipboardManager cm = (ClipboardManager)
				ctx.getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm == null) return;
		cm.setPrimaryClip(ClipData.newPlainText("Zerion", url));
		Toast.makeText(ctx, R.string.updates_link_copied, Toast.LENGTH_SHORT)
				.show();
	}
}

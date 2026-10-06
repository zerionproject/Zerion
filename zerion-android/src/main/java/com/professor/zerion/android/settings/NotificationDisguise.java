package com.professor.zerion.android.settings;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.DrawableRes;

@NotNullByDefault
public final class NotificationDisguise {

	private NotificationDisguise() {
	}

	public static boolean active(Context ctx) {
		try {
			return AppIconManager.getCurrentIcon(ctx)
					!= AppIconManager.ICON_DEFAULT;
		} catch (RuntimeException e) {
			return false;
		}
	}

	public static CharSequence title(Context ctx) {
		if (!active(ctx)) return ctx.getText(R.string.app_name);
		return disguiseLabel(ctx);
	}

	public static CharSequence title(Context ctx, int titleRes) {
		if (!active(ctx)) return ctx.getText(titleRes);
		return disguiseLabel(ctx);
	}

	public static CharSequence text(Context ctx, int textRes) {
		if (!active(ctx)) return ctx.getText(textRes);
		return ctx.getText(R.string.disguised_notification_text);
	}

	@DrawableRes
	public static int smallIcon(Context ctx, @DrawableRes int icon) {
		return active(ctx) ? R.drawable.ic_notifications : icon;
	}

	private static CharSequence disguiseLabel(Context ctx) {
		ComponentName entry = AppIconManager.entryComponent(ctx);
		PackageManager pm = ctx.getPackageManager();
		try {
			ActivityInfo info = pm.getActivityInfo(entry,
					PackageManager.MATCH_DISABLED_COMPONENTS);
			CharSequence label = info.loadLabel(pm);
			if (label.length() > 0) return label;
		} catch (PackageManager.NameNotFoundException
				| RuntimeException ignored) {
		}
		return ctx.getText(R.string.disguised_notification_title);
	}
}

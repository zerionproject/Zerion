package com.professor.zerion.android.navdrawer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.contact.add.remote.AddContactActivity;

import org.zerionproject.core.util.ZerionLinks;

import javax.annotation.Nullable;

import static android.content.Intent.ACTION_SEND;
import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.EXTRA_TEXT;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.LINK_REGEX;

class IntentRouter {

	private static final int MAX_URI_LENGTH = 2048;

	static void handleExternalIntent(Context ctx, Intent i) {
		String action = i.getAction();
		if (i.getData() != null && i.getData().toString().length() > MAX_URI_LENGTH) {
			return;
		}
		Uri data = normalized(i.getData());
		String text = i.getStringExtra(EXTRA_TEXT);
		if (text != null) text = ZerionLinks.normalizeScheme(text);
		if (ACTION_VIEW.equals(action) && data != null
				&& ZerionLinks.SCHEME.equals(data.getScheme())
				&& LINK_REGEX.matcher(data.toString()).find()) {
			redirectSanitized(ctx, i, data, text, AddContactActivity.class);
		}
		else if (ACTION_SEND.equals(action) &&
				"text/plain".equals(i.getType()) &&
				text != null &&
				text.length() <= MAX_URI_LENGTH &&
				LINK_REGEX.matcher(text).find()) {
			redirectSanitized(ctx, i, data, text, AddContactActivity.class);
		}
	}

	@Nullable
	static Uri normalized(@Nullable Uri data) {
		if (data == null) return null;
		String link = data.toString();
		String normal = ZerionLinks.normalizeScheme(link);
		return normal.equals(link) ? data : Uri.parse(normal);
	}

	private static void redirectSanitized(Context ctx, Intent original,
			@Nullable Uri data, @Nullable String text,
			Class<? extends ZerionActivity> activityClass) {

		Intent clean = new Intent(ctx, activityClass);
		clean.addFlags(FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_NEW_TASK);

		if (data != null) {
			clean.setData(data);
		}
		if (original.getAction() != null) {
			clean.setAction(original.getAction());
		}
		if (text != null) {
			clean.putExtra(EXTRA_TEXT, text);
		}
		if (original.getType() != null) {
			clean.setType(original.getType());
		}
		ctx.startActivity(clean);
	}

}

package com.professor.zerion.android.navdrawer;

import android.content.Context;
import android.content.Intent;

import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.contact.add.remote.AddContactActivity;

import static android.content.Intent.ACTION_SEND;
import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.EXTRA_TEXT;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.LINK_REGEX;

class IntentRouter {

	// Maximum URI/deep link length to prevent abuse
	private static final int MAX_URI_LENGTH = 2048;

	static void handleExternalIntent(Context ctx, Intent i) {
		String action = i.getAction();
		if (i.getData() != null && i.getData().toString().length() > MAX_URI_LENGTH) {
			return;
		}
		if (ACTION_VIEW.equals(action) && "zerion".equals(i.getScheme())) {
			redirectSanitized(ctx, i, AddContactActivity.class);
		}
		else if (ACTION_SEND.equals(action) &&
				"text/plain".equals(i.getType()) &&
				i.getStringExtra(EXTRA_TEXT) != null &&
				i.getStringExtra(EXTRA_TEXT).length() <= MAX_URI_LENGTH &&
				LINK_REGEX.matcher(i.getStringExtra(EXTRA_TEXT)).find()) {
			redirectSanitized(ctx, i, AddContactActivity.class);
		}
	}

	private static void redirectSanitized(Context ctx, Intent original,
			Class<? extends ZerionActivity> activityClass) {
		// Create a clean intent — never forward untrusted extras
		Intent clean = new Intent(ctx, activityClass);
		clean.addFlags(FLAG_ACTIVITY_CLEAR_TOP);
		// Only propagate the data we actually need
		if (original.getData() != null) {
			clean.setData(original.getData());
		}
		if (original.getAction() != null) {
			clean.setAction(original.getAction());
		}
		String text = original.getStringExtra(EXTRA_TEXT);
		if (text != null) {
			clean.putExtra(EXTRA_TEXT, text);
		}
		if (original.getType() != null) {
			clean.setType(original.getType());
		}
		ctx.startActivity(clean);
	}

}

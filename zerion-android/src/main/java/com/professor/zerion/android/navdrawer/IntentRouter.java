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

	static void handleExternalIntent(Context ctx, Intent i) {
		String action = i.getAction();
		if (ACTION_VIEW.equals(action) && "zerion".equals(i.getScheme())) {
			redirect(ctx, i, AddContactActivity.class);
		}
		else if (ACTION_SEND.equals(action) &&
				"text/plain".equals(i.getType()) &&
				i.getStringExtra(EXTRA_TEXT) != null &&
				LINK_REGEX.matcher(i.getStringExtra(EXTRA_TEXT)).find()) {
			redirect(ctx, i, AddContactActivity.class);
		}
	}

	private static void redirect(Context ctx, Intent i,
			Class<? extends ZerionActivity> activityClass) {
		i.setClass(ctx, activityClass);
		i.addFlags(FLAG_ACTIVITY_CLEAR_TOP);
		ctx.startActivity(i);
	}

}

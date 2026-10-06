package com.professor.zerion.android.navdrawer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.professor.zerion.android.channel.ChannelInviteHandlerActivity;
import com.professor.zerion.android.util.SafeIntents;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;

import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ExternalLinkActivity extends Activity {

	static final int MAX_URI_LENGTH = 2048;

	@Override
	protected void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		Intent i = getIntent();
		if (state == null && i != null) {
			SafeIntents.dropUnreadableExtras(i);
			try {
				route(this, i);
			} catch (RuntimeException ignored) {
			}
		}
		finish();
	}

	static void route(Context ctx, Intent i) {
		Uri data = i.getData();
		if (ACTION_VIEW.equals(i.getAction()) && data != null
				&& "zerion".equals(data.getScheme())
				&& "channel".equals(data.getHost())) {
			String link = data.toString();
			if (link.length() > MAX_URI_LENGTH) return;
			Intent open = new Intent(ctx, ChannelInviteHandlerActivity.class);
			open.setData(Uri.parse(link));
			open.addFlags(FLAG_ACTIVITY_NEW_TASK);
			ctx.startActivity(open);
			return;
		}
		IntentRouter.handleExternalIntent(ctx, i);
	}
}

package com.professor.zerion.android.channel;

import android.content.Context;

import com.professor.zerion.R;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.zerionproject.app.api.channel.ChannelState;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
final class ChannelAccessLabel {

	private ChannelAccessLabel() {
	}

	static String access(Context ctx, ChannelState s) {
		if (s.isPublicChannel()) {
			return ctx.getString(R.string.channels_row_public_channel);
		}
		String access = ctx.getString(R.string.channels_row_private);
		return s.requiresApproval()
				? ctx.getString(R.string.channels_label_parts, access,
						ctx.getString(R.string.channels_row_approval_required))
				: access;
	}

	static String withRole(Context ctx, ChannelState s) {
		String access = access(ctx, s);
		return s.weArePublisher()
				? ctx.getString(R.string.channels_label_parts, access,
						ctx.getString(R.string.channels_row_owner_self))
				: access;
	}

	static String withRole(Context ctx, ChannelState s, boolean editor) {
		if (s.weArePublisher() || !editor) return withRole(ctx, s);
		return ctx.getString(R.string.channels_label_parts, access(ctx, s),
				ctx.getString(R.string.channels_settings_role_editor));
	}
}

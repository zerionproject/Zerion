package com.professor.zerion.android.conversation.voice;

import android.content.Context;
import android.content.Intent;

import javax.annotation.Nullable;

class CallIntents {

	static final String ACTION_ACCEPT_CALL = "ACTION_ACCEPT_CALL";
	static final String ACTION_DECLINE_CALL = "ACTION_DECLINE_CALL";
	static final String EXTRA_AUTO_VIDEO = "auto_video";

	private CallIntents() {
	}

	static void launchCallActivity(Context context, int contactId,
			boolean isIncoming, String callId) {
		Intent intent = new Intent(context, VoiceCallActivity.class);
		intent.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, contactId);
		intent.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, isIncoming);
		intent.putExtra(VoiceCallActivity.EXTRA_CALL_ID, callId);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		context.startActivity(intent);
	}

	static Intent incomingCall(Context context, int contactId,
			@Nullable String callId, boolean video) {
		Intent intent = new Intent(context, VoiceCallActivity.class);
		intent.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, contactId);
		intent.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, true);
		intent.putExtra(EXTRA_AUTO_VIDEO, video);
		if (callId != null) {
			intent.putExtra(VoiceCallActivity.EXTRA_CALL_ID, callId);
		}
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		return intent;
	}
}

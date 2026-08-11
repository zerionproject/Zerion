package com.professor.zerion.android.conversation.voice;

import android.content.Context;
import android.content.Intent;

class CallIntents {

	static final String ACTION_ACCEPT_CALL = "ACTION_ACCEPT_CALL";
	static final String ACTION_DECLINE_CALL = "ACTION_DECLINE_CALL";
	static final String ACTION_SIGNALING = "com.professor.zerion.VOICE_CALL_SIGNALING";
	static final String EXTRA_SIGNALING_MESSAGE = "signaling_message";

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
}

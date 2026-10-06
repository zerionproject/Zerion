package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
final class VideoAutoAccept {

	private boolean armed;

	synchronized void callStarted(boolean answeredAsIncomingVideoCall) {
		armed = answeredAsIncomingVideoCall;
	}

	synchronized boolean consumeForOffer() {
		boolean accept = armed;
		armed = false;
		return accept;
	}

	synchronized void videoEnded() {
		armed = false;
	}

	synchronized boolean isArmed() {
		return armed;
	}
}

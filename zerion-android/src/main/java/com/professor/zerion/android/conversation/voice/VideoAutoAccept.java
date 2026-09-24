package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Whether the next video offer from the peer is accepted without asking.
 * Only the local user arms this, by answering a call that was offered as a
 * video call, and it is consumed by the first offer or dropped when the
 * video session ends or the peer withdraws. Nothing the peer sends later,
 * including another call offer flagged as video while this call is active,
 * can arm it: the camera starts only after a local answer.
 */
@NotNullByDefault
final class VideoAutoAccept {

	private boolean armed;

	/** Called once when the call starts, with what the user answered. */
	synchronized void callStarted(boolean answeredAsIncomingVideoCall) {
		armed = answeredAsIncomingVideoCall;
	}

	/** True exactly once after an arming answer; later offers are asked. */
	synchronized boolean consumeForOffer() {
		boolean accept = armed;
		armed = false;
		return accept;
	}

	/** The peer ended or rejected video: a new offer must be asked again. */
	synchronized void videoEnded() {
		armed = false;
	}

	synchronized boolean isArmed() {
		return armed;
	}
}

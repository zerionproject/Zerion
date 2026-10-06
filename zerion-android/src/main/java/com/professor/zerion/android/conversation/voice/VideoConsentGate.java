package com.professor.zerion.android.conversation.voice;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
final class VideoConsentGate {

	enum OfferDecision {
		PROMPT_USER,
		REJECT_NOT_ALLOWED,
		IGNORE
	}

	private boolean remoteOfferPending = false;
	private boolean localRequestOutstanding = false;
	private boolean captureArmed = false;
	private boolean videoWanted = false;

	synchronized void onLocalVideoCallPlaced() {
		videoWanted = true;
	}

	synchronized OfferDecision onRemoteOffer(boolean connected,
			boolean videoAllowedLocally) {
		if (!connected) return OfferDecision.IGNORE;
		if (!videoAllowedLocally) return OfferDecision.REJECT_NOT_ALLOWED;
		if (captureArmed) return OfferDecision.IGNORE;
		remoteOfferPending = true;
		return OfferDecision.PROMPT_USER;
	}

	synchronized boolean onLocalAccept(boolean connected,
			boolean videoAllowedLocally) {
		if (!remoteOfferPending || !connected || !videoAllowedLocally) {
			remoteOfferPending = false;
			return false;
		}
		remoteOfferPending = false;
		captureArmed = true;
		videoWanted = true;
		return true;
	}

	synchronized void onLocalRejectOrTimeout() {
		remoteOfferPending = false;
	}

	synchronized boolean onLocalRequest(boolean connected,
			boolean videoAllowedLocally) {
		if (!connected || !videoAllowedLocally || captureArmed) return false;
		localRequestOutstanding = true;
		captureArmed = true;
		videoWanted = true;
		return true;
	}

	synchronized boolean onAutoRequest(boolean connected,
			boolean videoAllowedLocally) {
		if (!videoWanted) return false;
		return onLocalRequest(connected, videoAllowedLocally);
	}

	synchronized void onLocalVideoOff() {
		remoteOfferPending = false;
		localRequestOutstanding = false;
		captureArmed = false;
		videoWanted = false;
	}

	synchronized boolean onRemoteAccept() {
		if (!localRequestOutstanding) return false;
		localRequestOutstanding = false;
		return true;
	}

	synchronized void onRemoteReject() {
		localRequestOutstanding = false;
		captureArmed = false;
		videoWanted = false;
	}

	synchronized void onRemoteVideoEnd() {
		onLocalVideoOff();
	}

	synchronized boolean mayStartCapture(boolean connected,
			boolean videoAllowedLocally) {
		return captureArmed && connected && videoAllowedLocally;
	}

	synchronized void reset() {
		remoteOfferPending = false;
		localRequestOutstanding = false;
		captureArmed = false;
		videoWanted = false;
	}

	synchronized boolean isCaptureArmed() {
		return captureArmed;
	}

	synchronized boolean isRemoteOfferPending() {
		return remoteOfferPending;
	}

	synchronized boolean isVideoWanted() {
		return videoWanted;
	}
}

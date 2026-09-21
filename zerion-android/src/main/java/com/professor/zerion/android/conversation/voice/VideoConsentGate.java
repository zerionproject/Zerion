package com.professor.zerion.android.conversation.voice;

import javax.annotation.concurrent.ThreadSafe;

/**
 * The local camera consent invariant for a call. Remote signalling can ask
 * for video and can answer our own request, but nothing a peer sends can
 * arm the local camera: capture is armed only by a local user action
 * (accepting a pending remote offer, or requesting video ourselves), and
 * only while the call is connected, video calls are enabled in settings and
 * the camera permission is granted. Every remote transition that would
 * otherwise start capture is answered with a decision the service turns
 * into a signal or a closed connection.
 */
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

	/**
	 * A remote VIDEO_OFFER arrived. Never arms capture.
	 */
	synchronized OfferDecision onRemoteOffer(boolean connected,
			boolean videoAllowedLocally) {
		if (!connected) return OfferDecision.IGNORE;
		if (!videoAllowedLocally) return OfferDecision.REJECT_NOT_ALLOWED;
		if (captureArmed) return OfferDecision.IGNORE;
		remoteOfferPending = true;
		return OfferDecision.PROMPT_USER;
	}

	/**
	 * The local user accepted the pending remote offer. Arms capture only if
	 * an offer is actually pending and video is allowed right now.
	 */
	synchronized boolean onLocalAccept(boolean connected,
			boolean videoAllowedLocally) {
		if (!remoteOfferPending || !connected || !videoAllowedLocally) {
			remoteOfferPending = false;
			return false;
		}
		remoteOfferPending = false;
		captureArmed = true;
		return true;
	}

	/**
	 * The local user declined the pending remote offer, or it timed out.
	 */
	synchronized void onLocalRejectOrTimeout() {
		remoteOfferPending = false;
	}

	/**
	 * The local user asked for video. Arms capture; the peer's answer only
	 * decides whether a connection is made.
	 */
	synchronized boolean onLocalRequest(boolean connected,
			boolean videoAllowedLocally) {
		if (!connected || !videoAllowedLocally || captureArmed) return false;
		localRequestOutstanding = true;
		captureArmed = true;
		return true;
	}

	/**
	 * A remote VIDEO_ACCEPT arrived. It may only proceed if it answers a
	 * request the local user made; an unsolicited accept must be ignored.
	 */
	synchronized boolean onRemoteAccept() {
		if (!localRequestOutstanding) return false;
		localRequestOutstanding = false;
		return true;
	}

	/**
	 * A remote VIDEO_REJECT arrived.
	 */
	synchronized void onRemoteReject() {
		localRequestOutstanding = false;
		captureArmed = false;
	}

	/**
	 * A video transport connection arrived or was established. Capture may
	 * start only if a local action armed it and video is still allowed.
	 */
	synchronized boolean mayStartCapture(boolean connected,
			boolean videoAllowedLocally) {
		return captureArmed && connected && videoAllowedLocally;
	}

	/**
	 * Video streaming stopped, failed or the call ended: everything is
	 * disarmed so that a later session needs fresh consent.
	 */
	synchronized void reset() {
		remoteOfferPending = false;
		localRequestOutstanding = false;
		captureArmed = false;
	}

	synchronized boolean isCaptureArmed() {
		return captureArmed;
	}

	synchronized boolean isRemoteOfferPending() {
		return remoteOfferPending;
	}
}

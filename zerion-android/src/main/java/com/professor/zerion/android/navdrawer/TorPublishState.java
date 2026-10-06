package com.professor.zerion.android.navdrawer;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
final class TorPublishState {

	static final long NO_CHECK = -1;
	static final long SKEW_RECHECK_MS = 30_000;
	static final long REPUBLISH_GRACE_MS = 15_000;

	private final PublishGrace grace;
	private final PublishGrace republish = new PublishGrace(REPUBLISH_GRACE_MS);
	private boolean active = false;
	private boolean published = false;
	private boolean dropped = false;
	private long inactiveSince = 0;
	private long skewSeconds = 0;

	TorPublishState(long graceMs) {
		grace = new PublishGrace(graceMs);
	}

	long onActive(long nowElapsed, long currentSkew) {
		active = true;
		if (inactiveSince != 0) {
			if (nowElapsed - inactiveSince >= REPUBLISH_GRACE_MS) dropped = true;
			inactiveSince = 0;
		}
		if (published) return NO_CHECK;
		if (currentSkew != 0) {
			skewSeconds = currentSkew;
			return SKEW_RECHECK_MS;
		}
		if (skewSeconds != 0) {
			skewSeconds = 0;
			restartWaits();
		}
		return remainingWait(nowElapsed);
	}

	long onInactive(boolean fullStop, long nowElapsed) {
		active = false;
		published = false;
		if (fullStop) {
			restartWaits();
			skewSeconds = 0;
		} else if (inactiveSince == 0) {
			inactiveSince = nowElapsed;
		}
		return skewSeconds != 0 ? SKEW_RECHECK_MS : NO_CHECK;
	}

	long onClockSkew(long skewSecondsReported) {
		skewSeconds = skewSecondsReported;
		published = false;
		return SKEW_RECHECK_MS;
	}

	void onPublishProof() {
		skewSeconds = 0;
		markPublished();
	}

	long onCheck(long nowElapsed, long currentSkew) {
		if (skewSeconds != 0 && currentSkew == 0) {
			skewSeconds = 0;
			restartWaits();
			if (!active || published) return NO_CHECK;
			return remainingWait(nowElapsed);
		}
		if (currentSkew != 0 && !published) {
			skewSeconds = currentSkew;
			return SKEW_RECHECK_MS;
		}
		if (!active || published) return NO_CHECK;
		long remaining = remainingWait(nowElapsed);
		if (remaining > 0) return remaining;
		markPublished();
		return NO_CHECK;
	}

	boolean isPublished() {
		return published;
	}

	long getSkewSeconds() {
		return skewSeconds;
	}

	private long remainingWait(long nowElapsed) {
		long remaining = grace.onActive(nowElapsed);
		if (remaining > 0) return remaining;
		return dropped ? republish.onActive(nowElapsed) : 0;
	}

	private void markPublished() {
		published = true;
		republish.reset();
		dropped = false;
	}

	private void restartWaits() {
		grace.reset();
		republish.reset();
		dropped = false;
		inactiveSince = 0;
	}
}

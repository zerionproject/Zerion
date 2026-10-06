package com.professor.zerion.android.navdrawer;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
final class PublishGrace {

	private final long graceMs;
	private long deadlineElapsed = 0;

	PublishGrace(long graceMs) {
		this.graceMs = graceMs;
	}

	long onActive(long nowElapsed) {
		if (deadlineElapsed == 0) {
			deadlineElapsed = nowElapsed + graceMs;
		}
		long remaining = deadlineElapsed - nowElapsed;
		return remaining < 0 ? 0 : remaining;
	}

	void reset() {
		deadlineElapsed = 0;
	}

	boolean isArmed() {
		return deadlineElapsed != 0;
	}
}

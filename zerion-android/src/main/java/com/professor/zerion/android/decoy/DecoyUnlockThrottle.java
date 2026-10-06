package com.professor.zerion.android.decoy;

import org.zerionproject.core.account.LoginThrottle;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;

@NotNullByDefault
final class DecoyUnlockThrottle {

	private final LoginThrottle throttle;

	DecoyUnlockThrottle(File stateFile) {
		throttle = LoginThrottle.inFile(stateFile, LoginThrottle.VAULT);
	}

	boolean allow() {
		return throttle.remainingLockoutMs() <= 0;
	}

	void failed() {
		throttle.recordFailure();
	}

	void passed() {
		throttle.reset();
	}
}

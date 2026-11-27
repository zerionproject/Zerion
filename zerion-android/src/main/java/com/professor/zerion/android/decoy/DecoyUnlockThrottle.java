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

	DecoyUnlockThrottle(LoginThrottle.Store store) {
		throttle = new LoginThrottle(store,
				android.os.SystemClock::elapsedRealtime,
				LoginThrottle.linuxBootId(), LoginThrottle.VAULT);
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

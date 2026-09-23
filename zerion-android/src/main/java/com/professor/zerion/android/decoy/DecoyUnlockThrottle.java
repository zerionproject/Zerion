package com.professor.zerion.android.decoy;

import org.zerionproject.core.account.LoginThrottle;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;

/**
 * Attempts at the decoy unlock code are throttled with the vault policy
 * (an immediate, doubling lockout that decays), persisted across process
 * restarts, so the digits-only code cannot be enumerated at the rate of the
 * key derivation alone. While locked, the calculator simply calculates.
 */
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

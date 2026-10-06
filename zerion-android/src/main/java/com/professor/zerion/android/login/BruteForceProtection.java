package com.professor.zerion.android.login;

import android.content.SharedPreferences;

import org.zerionproject.core.account.LoginThrottle;
import org.zerionproject.core.api.account.AccountManager;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class BruteForceProtection {

	static final int ATTEMPTS_BEFORE_FIRST_LOCKOUT = 3;
	static final int ATTEMPTS_BEFORE_WIPE =
			SignInErasePolicy.ATTEMPTS_BEFORE_WIPE;
	static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000;
	static final long MAX_LOCKOUT_MS = 24 * 60 * 60 * 1000;

	private final SignInErasePolicy erasePolicy;
	private final AccountManager accountManager;

	public BruteForceProtection(SharedPreferences prefs,
			AccountManager accountManager) {
		this.erasePolicy = new SignInErasePolicy(prefs);
		this.accountManager = accountManager;
	}

	public synchronized boolean isWipeOnRepeatedFailures() {
		return erasePolicy.isEnabled();
	}

	public synchronized void setWipeOnRepeatedFailures(boolean enabled) {
		erasePolicy.setEnabled(enabled);
	}

	public synchronized FailureResult recordFailedAttempt() {
		int failedAttempts = accountManager.failedSignInAttempts();
		long lockout = accountManager.signInLockoutRemainingMs();
		boolean wipe = isWipeOnRepeatedFailures();
		if (accountManager.isEraseRequested()) {
			return FailureResult.wipeData();
		}
		if (failedAttempts >= ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
			if (wipe && failedAttempts > ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
				return FailureResult.finalWarning(
						Math.max(1, ATTEMPTS_BEFORE_WIPE - failedAttempts));
			}
			return FailureResult.lockout(lockout);
		}
		return FailureResult.normalFailure(
				ATTEMPTS_BEFORE_FIRST_LOCKOUT - failedAttempts);
	}

	static long lockoutDurationFor(int failedAttempts) {
		return LoginThrottle.SIGN_IN.lockoutMs(failedAttempts);
	}

	public synchronized void recordSuccessfulLogin() {
	}

	public synchronized LockStatus checkLockStatus() {
		long remaining = accountManager.signInLockoutRemainingMs();
		if (remaining > 0) return LockStatus.locked(remaining);
		return LockStatus.notLocked();
	}

	public synchronized void clear() {
	}

	public static final class FailureResult {

		public enum Type {
			NORMAL_FAILURE,
			LOCKOUT,
			FINAL_WARNING,
			WIPE_DATA
		}

		public final Type type;
		public final int attemptsRemaining;
		public final long lockoutDurationMs;

		private FailureResult(Type type, int attemptsRemaining,
				long lockoutDurationMs) {
			this.type = type;
			this.attemptsRemaining = attemptsRemaining;
			this.lockoutDurationMs = lockoutDurationMs;
		}

		public static FailureResult normalFailure(int attemptsRemaining) {
			return new FailureResult(Type.NORMAL_FAILURE,
					attemptsRemaining, 0);
		}

		public static FailureResult lockout(long durationMs) {
			return new FailureResult(Type.LOCKOUT, 0, durationMs);
		}

		public static FailureResult finalWarning(int attemptsRemaining) {
			return new FailureResult(Type.FINAL_WARNING,
					attemptsRemaining, 0);
		}

		public static FailureResult wipeData() {
			return new FailureResult(Type.WIPE_DATA, 0, 0);
		}
	}

	public static final class LockStatus {

		public final boolean isLocked;
		public final long remainingMs;

		private LockStatus(boolean isLocked, long remainingMs) {
			this.isLocked = isLocked;
			this.remainingMs = remainingMs;
		}

		public static LockStatus locked(long remainingMs) {
			return new LockStatus(true, remainingMs);
		}

		public static LockStatus notLocked() {
			return new LockStatus(false, 0);
		}

		public int getRemainingMinutes() {
			return (int) (remainingMs / 60000);
		}
	}
}

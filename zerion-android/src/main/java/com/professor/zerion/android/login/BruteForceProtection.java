package com.professor.zerion.android.login;

import android.content.SharedPreferences;

import org.zerionproject.core.account.LoginThrottle;
import org.zerionproject.core.api.account.AccountManager;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Sign-in failure policy shown to the user. The counting and the lockout
 * timing live in the account manager's single persisted throttle, which
 * runs on the monotonic clock and survives restarts; this class only turns
 * that state into what the login screen shows and applies the opt-in
 * erase policy, whose flag is the one thing it stores itself.
 */
@ThreadSafe
@NotNullByDefault
public final class BruteForceProtection {

	private static final String KEY_WIPE_ON_FAILURES = "bf_wipe";

	static final int ATTEMPTS_BEFORE_FIRST_LOCKOUT = 3;
	static final int ATTEMPTS_BEFORE_WIPE = 6;
	static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000;
	static final long MAX_LOCKOUT_MS = 24 * 60 * 60 * 1000;

	private final SharedPreferences prefs;
	private final AccountManager accountManager;

	public BruteForceProtection(SharedPreferences prefs,
			AccountManager accountManager) {
		this.prefs = prefs;
		this.accountManager = accountManager;
	}

	public synchronized boolean isWipeOnRepeatedFailures() {
		return prefs.getBoolean(KEY_WIPE_ON_FAILURES, false);
	}

	public synchronized void setWipeOnRepeatedFailures(boolean enabled) {
		prefs.edit().putBoolean(KEY_WIPE_ON_FAILURES, enabled).commit();
	}

	/**
	 * Called after the account manager refused a password; the manager has
	 * already counted the failure and started any lockout.
	 */
	public synchronized FailureResult recordFailedAttempt() {
		int failedAttempts = accountManager.failedSignInAttempts();
		long lockout = accountManager.signInLockoutRemainingMs();
		boolean wipe = isWipeOnRepeatedFailures();
		if (wipe && failedAttempts >= ATTEMPTS_BEFORE_WIPE) {
			return FailureResult.wipeData();
		}
		if (failedAttempts >= ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
			if (wipe && failedAttempts > ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
				return FailureResult.finalWarning(
						ATTEMPTS_BEFORE_WIPE - failedAttempts);
			}
			return FailureResult.lockout(lockout);
		}
		return FailureResult.normalFailure(
				ATTEMPTS_BEFORE_FIRST_LOCKOUT - failedAttempts);
	}

	static long lockoutDurationFor(int failedAttempts) {
		return LoginThrottle.SIGN_IN.lockoutMs(failedAttempts);
	}

	/** The manager resets its throttle on a successful sign-in. */
	public synchronized void recordSuccessfulLogin() {
	}

	public synchronized LockStatus checkLockStatus() {
		long remaining = accountManager.signInLockoutRemainingMs();
		if (remaining > 0) return LockStatus.locked(remaining);
		return LockStatus.notLocked();
	}

	/** The manager clears its throttle when the account is deleted. */
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

package com.professor.zerion.android.login;

import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class BruteForceProtection {

	private static final String KEY_FAILED_ATTEMPTS = "bf_fa";
	private static final String KEY_LOCKOUT_UNTIL = "bf_lu";
	private static final String KEY_LAST_FAILED = "bf_lf";

	private int failedAttempts = 0;
	private long lockoutUntilWallClock = 0;
	private long lastFailedWallClock = 0;

	private static final int ATTEMPTS_BEFORE_FIRST_LOCKOUT = 3;
	private static final int ATTEMPTS_BEFORE_WIPE = 6;
	private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000;

	private final SharedPreferences prefs;

	public BruteForceProtection(SharedPreferences prefs) {
		this.prefs = prefs;
		loadState();
	}

	public synchronized FailureResult recordFailedAttempt() {
		long now = System.currentTimeMillis();

		failedAttempts++;
		lastFailedWallClock = now;

		if (failedAttempts >= ATTEMPTS_BEFORE_WIPE) {
			saveState();
			return FailureResult.wipeData();
		}

		if (failedAttempts >= ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
			lockoutUntilWallClock = now + LOCKOUT_DURATION_MS;
			saveState();
			if (failedAttempts == ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
				return FailureResult.lockout(LOCKOUT_DURATION_MS);
			}
			return FailureResult.finalWarning(
					ATTEMPTS_BEFORE_WIPE - failedAttempts);
		}

		saveState();
		int remaining = ATTEMPTS_BEFORE_FIRST_LOCKOUT - failedAttempts;
		return FailureResult.normalFailure(remaining);
	}

	public synchronized void recordSuccessfulLogin() {
		failedAttempts = 0;
		lastFailedWallClock = 0;
		lockoutUntilWallClock = 0;
		saveState();
	}

	public synchronized LockStatus checkLockStatus() {
		long now = System.currentTimeMillis();

		if (lockoutUntilWallClock == 0) {
			return LockStatus.notLocked();
		}

		if (now < lockoutUntilWallClock) {
			long remainingMs = lockoutUntilWallClock - now;
			return LockStatus.locked(remainingMs);
		} else {
			lockoutUntilWallClock = 0;
			saveState();
			return LockStatus.notLocked();
		}
	}

	public synchronized void clear() {
		failedAttempts = 0;
		lastFailedWallClock = 0;
		lockoutUntilWallClock = 0;
		deleteState();
	}

	private void loadState() {
		failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0);
		lockoutUntilWallClock = prefs.getLong(KEY_LOCKOUT_UNTIL, 0);
		lastFailedWallClock = prefs.getLong(KEY_LAST_FAILED, 0);
	}

	private void saveState() {
		prefs.edit()
				.putInt(KEY_FAILED_ATTEMPTS, failedAttempts)
				.putLong(KEY_LOCKOUT_UNTIL, lockoutUntilWallClock)
				.putLong(KEY_LAST_FAILED, lastFailedWallClock)
				.commit();
	}

	private void deleteState() {
		prefs.edit()
				.remove(KEY_FAILED_ATTEMPTS)
				.remove(KEY_LOCKOUT_UNTIL)
				.remove(KEY_LAST_FAILED)
				.commit();
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

package com.professor.zerion.android.login;

import android.os.SystemClock;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class BruteForceProtection {

	private int failedAttempts = 0;
	private long lockoutUntilElapsed = 0;
	private long lastFailedAttemptElapsed = 0;

	private static final int ATTEMPTS_BEFORE_FIRST_LOCKOUT = 3;
	private static final int ATTEMPTS_BEFORE_WIPE = 6;
	private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000;
	private static final long ATTEMPT_WINDOW_MS = 30 * 60 * 1000;

	public BruteForceProtection() {
	}

	public synchronized FailureResult recordFailedAttempt() {
		long now = SystemClock.elapsedRealtime();

		if (now - lastFailedAttemptElapsed > ATTEMPT_WINDOW_MS) {
			failedAttempts = 0;
		}

		failedAttempts++;
		lastFailedAttemptElapsed = now;

		if (failedAttempts >= ATTEMPTS_BEFORE_WIPE) {
			return FailureResult.wipeData();
		}

		if (failedAttempts == ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
			lockoutUntilElapsed = now + LOCKOUT_DURATION_MS;
			return FailureResult.lockout(LOCKOUT_DURATION_MS);
		}

		if (failedAttempts > ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
			int remaining = ATTEMPTS_BEFORE_WIPE - failedAttempts;
			return FailureResult.finalWarning(remaining);
		}

		int remaining = ATTEMPTS_BEFORE_FIRST_LOCKOUT - failedAttempts;
		return FailureResult.normalFailure(remaining);
	}

	public synchronized void recordSuccessfulLogin() {
		failedAttempts = 0;
		lastFailedAttemptElapsed = 0;
		lockoutUntilElapsed = 0;
	}

	public synchronized LockStatus checkLockStatus() {
		long now = SystemClock.elapsedRealtime();

		if (lockoutUntilElapsed == 0) {
			return LockStatus.notLocked();
		}

		if (now < lockoutUntilElapsed) {
			long remainingMs = lockoutUntilElapsed - now;
			return LockStatus.locked(remainingMs);
		} else {
			lockoutUntilElapsed = 0;
			return LockStatus.notLocked();
		}
	}

	public synchronized int getFailedAttempts() {
		long now = SystemClock.elapsedRealtime();
		if (now - lastFailedAttemptElapsed > ATTEMPT_WINDOW_MS) {
			return 0;
		}
		return failedAttempts;
	}

	public synchronized void clear() {
		failedAttempts = 0;
		lastFailedAttemptElapsed = 0;
		lockoutUntilElapsed = 0;
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

		private FailureResult(Type type, int attemptsRemaining, long lockoutDurationMs) {
			this.type = type;
			this.attemptsRemaining = attemptsRemaining;
			this.lockoutDurationMs = lockoutDurationMs;
		}

		public static FailureResult normalFailure(int attemptsRemaining) {
			return new FailureResult(Type.NORMAL_FAILURE, attemptsRemaining, 0);
		}

		public static FailureResult lockout(long durationMs) {
			return new FailureResult(Type.LOCKOUT, 0, durationMs);
		}

		public static FailureResult finalWarning(int attemptsRemaining) {
			return new FailureResult(Type.FINAL_WARNING, attemptsRemaining, 0);
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

		public int getRemainingSeconds() {
			return (int) ((remainingMs % 60000) / 1000);
		}
	}
}

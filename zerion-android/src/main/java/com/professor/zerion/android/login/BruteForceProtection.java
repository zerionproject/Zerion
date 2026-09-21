package com.professor.zerion.android.login;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.function.LongSupplier;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Slows down password guessing at sign-in. Three wrong passwords start a
 * lockout that doubles with every further failure, up to a day. Erasing the
 * account after repeated failures is a policy the user has to switch on;
 * by default a wrong password can only ever cost time, never data. The
 * failure counter decays a day after the last failure or lockout ended,
 * whichever is later. Lockouts are measured
 * on the monotonic clock as well as the wall clock, so moving the clock
 * forward does not shorten them within one boot.
 */
@ThreadSafe
@NotNullByDefault
public final class BruteForceProtection {

	private static final String KEY_FAILED_ATTEMPTS = "bf_fa";
	private static final String KEY_LOCKOUT_UNTIL = "bf_lu";
	private static final String KEY_LAST_FAILED = "bf_lf";
	private static final String KEY_WIPE_ON_FAILURES = "bf_wipe";

	static final int ATTEMPTS_BEFORE_FIRST_LOCKOUT = 3;
	static final int ATTEMPTS_BEFORE_WIPE = 6;
	static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000;
	static final long MAX_LOCKOUT_MS = 24 * 60 * 60 * 1000;
	static final long FAILURE_DECAY_MS = 24 * 60 * 60 * 1000;

	private final SharedPreferences prefs;
	private final LongSupplier wallClock;
	private final LongSupplier monotonicClock;

	private int failedAttempts = 0;
	private long lockoutUntilWallClock = 0;
	private long lockoutUntilMonotonic = 0;
	private long lastFailedWallClock = 0;

	public BruteForceProtection(SharedPreferences prefs) {
		this(prefs, System::currentTimeMillis, SystemClock::elapsedRealtime);
	}

	BruteForceProtection(SharedPreferences prefs, LongSupplier wallClock,
			LongSupplier monotonicClock) {
		this.prefs = prefs;
		this.wallClock = wallClock;
		this.monotonicClock = monotonicClock;
		loadState();
	}

	/** Whether repeated wrong passwords erase the account (off by default). */
	public synchronized boolean isWipeOnRepeatedFailures() {
		return prefs.getBoolean(KEY_WIPE_ON_FAILURES, false);
	}

	public synchronized void setWipeOnRepeatedFailures(boolean enabled) {
		prefs.edit().putBoolean(KEY_WIPE_ON_FAILURES, enabled).commit();
	}

	public synchronized FailureResult recordFailedAttempt() {
		long now = wallClock.getAsLong();
		long quietSince = Math.max(lastFailedWallClock, lockoutUntilWallClock);
		if (quietSince > 0 && now - quietSince > FAILURE_DECAY_MS) {
			failedAttempts = 0;
		}
		failedAttempts++;
		lastFailedWallClock = now;
		boolean wipe = isWipeOnRepeatedFailures();
		if (wipe && failedAttempts >= ATTEMPTS_BEFORE_WIPE) {
			saveState();
			return FailureResult.wipeData();
		}
		if (failedAttempts >= ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
			long duration = lockoutDurationFor(failedAttempts);
			lockoutUntilWallClock = now + duration;
			lockoutUntilMonotonic = monotonicClock.getAsLong() + duration;
			saveState();
			if (wipe && failedAttempts > ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
				return FailureResult.finalWarning(
						ATTEMPTS_BEFORE_WIPE - failedAttempts);
			}
			return FailureResult.lockout(duration);
		}
		saveState();
		int remaining = ATTEMPTS_BEFORE_FIRST_LOCKOUT - failedAttempts;
		return FailureResult.normalFailure(remaining);
	}

	/**
	 * Five minutes at the third failure, doubling with every further one and
	 * capped at a day.
	 */
	static long lockoutDurationFor(int failedAttempts) {
		int doublings = Math.max(0,
				failedAttempts - ATTEMPTS_BEFORE_FIRST_LOCKOUT);
		long duration = LOCKOUT_DURATION_MS << Math.min(doublings, 20);
		return Math.min(duration, MAX_LOCKOUT_MS);
	}

	public synchronized void recordSuccessfulLogin() {
		failedAttempts = 0;
		lastFailedWallClock = 0;
		lockoutUntilWallClock = 0;
		lockoutUntilMonotonic = 0;
		saveState();
	}

	public synchronized LockStatus checkLockStatus() {
		if (lockoutUntilWallClock == 0 && lockoutUntilMonotonic == 0) {
			return LockStatus.notLocked();
		}
		long remainingWall = lockoutUntilWallClock - wallClock.getAsLong();
		long remainingMonotonic =
				lockoutUntilMonotonic - monotonicClock.getAsLong();
		long remaining = Math.max(remainingWall, remainingMonotonic);
		if (remaining > 0) {
			return LockStatus.locked(remaining);
		}
		lockoutUntilWallClock = 0;
		lockoutUntilMonotonic = 0;
		saveState();
		return LockStatus.notLocked();
	}

	public synchronized void clear() {
		failedAttempts = 0;
		lastFailedWallClock = 0;
		lockoutUntilWallClock = 0;
		lockoutUntilMonotonic = 0;
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

package com.professor.zerion.android.login;

import android.content.Context;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class BruteForceProtection {

	private static final String STATE_FILE = ".bf_state";

	private int failedAttempts = 0;
	private long lockoutUntilWallClock = 0;
	private long lastFailedWallClock = 0;

	private static final int ATTEMPTS_BEFORE_FIRST_LOCKOUT = 3;
	private static final int ATTEMPTS_BEFORE_WIPE = 6;
	private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000;
	private static final long ATTEMPT_WINDOW_MS = 30 * 60 * 1000;

	private final File stateFile;

	public BruteForceProtection(Context context) {
		stateFile = new File(context.getFilesDir(), STATE_FILE);
		loadState();
	}

	public synchronized FailureResult recordFailedAttempt() {
		long now = System.currentTimeMillis();

		if (lastFailedWallClock > 0 &&
				now - lastFailedWallClock > ATTEMPT_WINDOW_MS) {
			failedAttempts = 0;
		}

		failedAttempts++;
		lastFailedWallClock = now;
		saveState();

		if (failedAttempts >= ATTEMPTS_BEFORE_WIPE) {
			return FailureResult.wipeData();
		}

		if (failedAttempts == ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
			lockoutUntilWallClock = now + LOCKOUT_DURATION_MS;
			saveState();
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

	public synchronized int getFailedAttempts() {
		long now = System.currentTimeMillis();
		if (lastFailedWallClock > 0 &&
				now - lastFailedWallClock > ATTEMPT_WINDOW_MS) {
			return 0;
		}
		return failedAttempts;
	}

	public synchronized void clear() {
		failedAttempts = 0;
		lastFailedWallClock = 0;
		lockoutUntilWallClock = 0;
		deleteState();
	}

	private void loadState() {
		if (!stateFile.exists()) return;
		try (BufferedReader reader = new BufferedReader(
				new FileReader(stateFile))) {
			String line1 = reader.readLine();
			String line2 = reader.readLine();
			String line3 = reader.readLine();
			if (line1 != null && line2 != null && line3 != null) {
				failedAttempts = Integer.parseInt(line1.trim());
				lockoutUntilWallClock = Long.parseLong(line2.trim());
				lastFailedWallClock = Long.parseLong(line3.trim());
			}
		} catch (IOException | NumberFormatException e) {
			// Corrupted state file — treat as fresh start
			failedAttempts = 0;
			lockoutUntilWallClock = 0;
			lastFailedWallClock = 0;
		}
	}

	private void saveState() {
		File tmpFile = new File(stateFile.getParent(),
				STATE_FILE + ".tmp");
		try (FileWriter writer = new FileWriter(tmpFile)) {
			writer.write(failedAttempts + "\n");
			writer.write(lockoutUntilWallClock + "\n");
			writer.write(lastFailedWallClock + "\n");
			writer.flush();
		} catch (IOException e) {
			return;
		}
		// Atomic rename to prevent corruption on crash
		tmpFile.renameTo(stateFile);
	}

	private void deleteState() {
		stateFile.delete();
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

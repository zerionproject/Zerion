package com.professor.zerion.android.security;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.WindowManager;

import com.professor.zerion.android.AppModule;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.briarproject.nullsafety.NotNullByDefault;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_SCREENSHOT_PROTECTION;

@Singleton
@NotNullByDefault
public final class SecurityManager {

	private volatile int failedAttempts = 0;
	private volatile long lockoutUntilElapsed = 0;
	private volatile long lastFailedAttemptElapsed = 0;

	private static final int MAX_FAILED_ATTEMPTS = 5;
	private static final long LOCKOUT_DURATION = 5 * 60 * 1000;
	private static final long ATTEMPT_WINDOW = 30 * 60 * 1000;

	private final SharedPreferences uiPrefs;

	@Inject
	public SecurityManager(Application application,
			@AppModule.UiPrefs SharedPreferences uiPrefs) {
		this.uiPrefs = uiPrefs;
	}

	public void applyScreenshotProtection(Activity activity) {
		boolean enabled = uiPrefs.getBoolean(PREF_SCREENSHOT_PROTECTION, true);
		if (enabled) {
			activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		} else {
			activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
		}
	}

	public boolean isScreenshotProtectionEnabled() {
		return uiPrefs.getBoolean(PREF_SCREENSHOT_PROTECTION, true);
	}

	public synchronized boolean isAccountLocked() {
		long now = SystemClock.elapsedRealtime();
		if (lockoutUntilElapsed > now) {
			return true;
		}
		if (lockoutUntilElapsed > 0 && lockoutUntilElapsed <= now) {
			clearLockout();
		}
		return false;
	}

	public synchronized long getRemainingLockoutTime() {
		long now = SystemClock.elapsedRealtime();
		if (lockoutUntilElapsed > now) {
			return lockoutUntilElapsed - now;
		}
		return 0;
	}

	public synchronized void recordFailedLogin() {
		long now = SystemClock.elapsedRealtime();

		if (now - lastFailedAttemptElapsed > ATTEMPT_WINDOW) {
			failedAttempts = 0;
		}

		failedAttempts++;
		lastFailedAttemptElapsed = now;

		if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
			lockoutUntilElapsed = now + LOCKOUT_DURATION;
		}
	}

	public synchronized void clearFailedLogins() {
		failedAttempts = 0;
		lastFailedAttemptElapsed = 0;
		lockoutUntilElapsed = 0;
	}

	private synchronized void clearLockout() {
		lockoutUntilElapsed = 0;
	}

	public synchronized int getFailedLoginCount() {
		long now = SystemClock.elapsedRealtime();
		if (now - lastFailedAttemptElapsed > ATTEMPT_WINDOW) {
			return 0;
		}
		return failedAttempts;
	}

	public SecurityStatus getSecurityStatus() {
		return new SecurityStatus();
	}

	public static final class SecurityStatus {

		public SecurityStatus() {
		}

		public boolean isSecure() {
			return true;
		}

		public int getThreatLevel() {
			return 0;
		}
	}
}

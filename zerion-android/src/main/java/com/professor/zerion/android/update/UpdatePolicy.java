package com.professor.zerion.android.update;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
final class UpdatePolicy {

	static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
	static final long RETRY_INTERVAL_MS = 60L * 60 * 1000;
	static final long LATER_MS = 24L * 60 * 60 * 1000;

	private UpdatePolicy() {
	}

	static boolean dueForAutomaticCheck(boolean enabled, boolean storeInstall,
			boolean torActive, long now, long nextCheckAt) {
		if (!enabled || storeInstall || !torActive) return false;
		return now >= nextCheckAt || nextCheckAt - now > CHECK_INTERVAL_MS;
	}

	static long nextCheckAfter(UpdateChecker.Outcome outcome, long now) {
		return outcome == UpdateChecker.Outcome.UNREACHABLE
				? now + RETRY_INTERVAL_MS : now + CHECK_INTERVAL_MS;
	}

	static boolean shouldPopUp(ReleaseAnnouncement a, long installedCode,
			long dismissedCode, long dismissedAt, long now) {
		if (a.versionCode <= installedCode) return false;
		boolean laterStillRunning = dismissedCode == a.versionCode
				&& now >= dismissedAt && now - dismissedAt < LATER_MS;
		return !laterStillRunning;
	}
}

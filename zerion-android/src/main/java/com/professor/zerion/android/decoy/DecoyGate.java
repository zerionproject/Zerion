package com.professor.zerion.android.decoy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;


import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Process-scoped decoy gate. When Decoy Mode is enabled and its unlock code is
 * set, no real app UI may render until the calculator code has been entered in
 * the current process. The passed flag is a plain static, so it resets on
 * process death (GrapheneOS aggressive kill included) - after a kill the gate is
 * required again, closing the "reopen via recents restores the real UI" bypass.
 */
@NotNullByDefault
public final class DecoyGate {

	private static volatile boolean passedThisProcess = false;

	private DecoyGate() {
	}

	public static void markPassed() {
		passedThisProcess = true;
	}

	public static boolean isPassed() {
		return passedThisProcess;
	}

	/**
	 * Pure gate decision. The calculator must be shown when Decoy Mode is
	 * configured and the code has not been entered in this process. Because the
	 * passed flag is process-scoped, a process kill (already passed becomes
	 * false again) makes the gate required once more - a reopen after the OS
	 * kills the app can never restore the real UI without the code.
	 */
	public static boolean decide(boolean passedThisProcess,
			boolean configuredWithCode) {
		return !passedThisProcess && configuredWithCode;
	}

	public static boolean required(Context ctx) {
		if (passedThisProcess) {
			return false;
		}
		boolean configuredWithCode;
		try {
			configuredWithCode = DecoyConfig.isEnabled(ctx)
					&& DecoyConfig.hasUnlockCode(ctx);
		} catch (Throwable t) {
			configuredWithCode = false;
		}
		return decide(passedThisProcess, configuredWithCode);
	}

	public static void redirectToCalculator(Activity activity) {
		Intent i = new Intent(activity, DecoyCalculatorActivity.class);
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
				| Intent.FLAG_ACTIVITY_CLEAR_TASK);
		activity.startActivity(i);
		activity.finish();
	}
}

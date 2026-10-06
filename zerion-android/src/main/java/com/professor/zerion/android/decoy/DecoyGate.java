package com.professor.zerion.android.decoy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;


import org.briarproject.nullsafety.NotNullByDefault;

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

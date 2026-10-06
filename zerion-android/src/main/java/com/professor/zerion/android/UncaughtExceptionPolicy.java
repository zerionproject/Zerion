package com.professor.zerion.android;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class UncaughtExceptionPolicy {

	private UncaughtExceptionPolicy() {
	}

	public static boolean endsProcess(Thread failed, Thread main) {
		return failed == main;
	}
}

package com.professor.zerion.android;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Which uncaught exceptions end the process in a release build. A failure on
 * the main thread leaves the user interface in an unknown state, so the
 * process ends at once and without a crash report. A failure on any other
 * thread ends only that thread: the executors replace a failed worker, the
 * validation, delivery hook and event listener paths contain their own
 * failures, and a peer or a message that can make one worker fail must not be
 * able to take the whole application down with it.
 */
@NotNullByDefault
public final class UncaughtExceptionPolicy {

	private UncaughtExceptionPolicy() {
	}

	public static boolean endsProcess(Thread failed, Thread main) {
		return failed == main;
	}
}

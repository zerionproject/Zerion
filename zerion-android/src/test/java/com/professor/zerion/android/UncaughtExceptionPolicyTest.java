package com.professor.zerion.android;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Only a main-thread failure ends the process; a worker, executor or
 * connection thread that fails ends alone, so no remote input that reaches a
 * worker can turn into a process kill.
 */
public class UncaughtExceptionPolicyTest {

	@Test
	public void onlyTheMainThreadEndsTheProcess() throws Exception {
		Thread main = Thread.currentThread();
		assertTrue(UncaughtExceptionPolicy.endsProcess(main, main));
		Thread worker = new Thread(() -> {
		}, "worker");
		assertFalse(UncaughtExceptionPolicy.endsProcess(worker, main));
		Thread[] seen = new Thread[1];
		Thread other = new Thread(() -> seen[0] = Thread.currentThread());
		other.start();
		other.join();
		assertFalse(UncaughtExceptionPolicy.endsProcess(seen[0], main));
	}
}

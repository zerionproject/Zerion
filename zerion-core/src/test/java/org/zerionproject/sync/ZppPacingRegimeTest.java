package org.zerionproject.sync;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZppPacingRegimeTest {

	@Test
	public void slotClockWaitsFullDelayWithoutActivity() throws Exception {
		ZppConnectionRunnerImpl.SlotClock clock =
				new ZppConnectionRunnerImpl.SlotClock();
		AtomicBoolean running = new AtomicBoolean(true);
		long start = System.currentTimeMillis();
		clock.awaitNextSlot(running, start, 200, 20);
		long elapsed = System.currentTimeMillis() - start;
		assertTrue("returned too early: " + elapsed, elapsed >= 180);
	}

	@Test
	public void activityShortensIdleGapToActiveSpacing() throws Exception {
		ZppConnectionRunnerImpl.SlotClock clock =
				new ZppConnectionRunnerImpl.SlotClock();
		AtomicBoolean running = new AtomicBoolean(true);
		long start = System.currentTimeMillis();
		Thread waker = new Thread(() -> {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				return;
			}
			clock.noteActivity();
		});
		waker.start();
		clock.awaitNextSlot(running, start, 2_000, 100);
		long elapsed = System.currentTimeMillis() - start;
		waker.join();
		assertTrue("did not snap to active spacing: " + elapsed,
				elapsed < 1_000);
		assertTrue("snapped below active spacing: " + elapsed, elapsed >= 90);
	}

	@Test
	public void activityNeverShortensAnActiveGap() throws Exception {
		ZppConnectionRunnerImpl.SlotClock clock =
				new ZppConnectionRunnerImpl.SlotClock();
		AtomicBoolean running = new AtomicBoolean(true);
		long start = System.currentTimeMillis();
		Thread waker = new Thread(clock::noteActivity);
		waker.start();
		clock.awaitNextSlot(running, start, 150, 150);
		long elapsed = System.currentTimeMillis() - start;
		waker.join();
		assertTrue("active gap was shortened: " + elapsed, elapsed >= 130);
	}

	@Test
	public void schedulerReportsRealFramesAndSignalsQueueing()
			throws Exception {
		AtomicInteger sent = new AtomicInteger();
		ZppSendScheduler scheduler =
				new ZppSendScheduler(record -> sent.incrementAndGet());
		AtomicInteger woken = new AtomicInteger();
		scheduler.setWakeListener(woken::incrementAndGet);
		assertFalse(scheduler.tick());
		scheduler.enqueue(7, new byte[] {1, 2, 3});
		assertTrue(woken.get() == 1);
		assertTrue(scheduler.tick());
		assertFalse(scheduler.tick());
	}
}

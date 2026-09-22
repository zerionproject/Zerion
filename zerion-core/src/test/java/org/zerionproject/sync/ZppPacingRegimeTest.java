package org.zerionproject.sync;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

	/**
	 * NET-10: a peer that only sends cannot hold this side at the active
	 * cadence. Without a local real send, receipts start one active window
	 * per activation interval; within the reply window of a local send every
	 * receipt extends the window; once that window has passed, receipts fall
	 * back to the rationed activation.
	 */
	@Test
	public void receiptsAloneActivateOncePerInterval() {
		AtomicLong now = new AtomicLong(1_000_000L);
		ZppConnectionRunnerImpl.ReceiveActivityGate gate =
				new ZppConnectionRunnerImpl.ReceiveActivityGate(now::get,
						10_000L, 60_000L);
		assertTrue("first receipt opens one window", gate.admitReceipt());
		for (int i = 0; i < 59; i++) {
			now.addAndGet(1_000L);
			assertFalse("receipt " + i + " must not extend",
					gate.admitReceipt());
		}
		now.addAndGet(1_000L);
		assertTrue("next window after the interval", gate.admitReceipt());
		now.addAndGet(1_000L);
		assertFalse(gate.admitReceipt());
	}

	@Test
	public void receiptsExtendWhileThisSideIsReplying() {
		AtomicLong now = new AtomicLong(1_000_000L);
		ZppConnectionRunnerImpl.ReceiveActivityGate gate =
				new ZppConnectionRunnerImpl.ReceiveActivityGate(now::get,
						10_000L, 60_000L);
		gate.noteLocalSend();
		for (int i = 0; i < 10; i++) {
			now.addAndGet(1_000L);
			assertTrue("within the reply window", gate.admitReceipt());
		}
		now.addAndGet(1L);
		assertTrue("reply window over: one rationed activation",
				gate.admitReceipt());
		now.addAndGet(1_000L);
		assertFalse("then rationed", gate.admitReceipt());
		gate.noteLocalSend();
		assertTrue("a reply re-opens the window", gate.admitReceipt());
	}

	@Test
	public void idleCadenceDoesNotDependOnTheNetworkType() {
		ZppPacingPolicy policy = new ZppPacingPolicy();
		assertTrue(policy.idleIntervalMs() == ZppPacingPolicy.IDLE_INTERVAL_MS);
		assertTrue(policy.activeIntervalMs()
				== ZppPacingPolicy.ACTIVE_INTERVAL_MS);
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

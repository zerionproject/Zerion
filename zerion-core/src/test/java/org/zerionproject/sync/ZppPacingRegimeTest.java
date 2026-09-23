package org.zerionproject.sync;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

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
	/**
	 * A2-REG-NET-04: a protocol reply the peer provoked (an offer answered
	 * with a request, a delivery answered with an ack) is a real frame but
	 * not local activity; only a message this side produced opens the reply
	 * window in which receipts extend the active regime.
	 */
	@Test
	public void provokedRepliesDoNotCountAsLocalActivity() throws Exception {
		java.util.List<byte[]> sent = new java.util.ArrayList<>();
		ZppSendScheduler scheduler = new ZppSendScheduler(sent::add,
				() -> true);
		scheduler.enqueueRecord(new byte[] {0x10, 1, 2}, false);
		assertTrue(scheduler.tick());
		assertFalse("a reply is not user originated",
				scheduler.lastRealFrameWasUserOriginated());
		scheduler.enqueueRecord(new byte[] {0x10, 3, 4}, true);
		assertTrue(scheduler.tick());
		assertTrue(scheduler.lastRealFrameWasUserOriginated());
		assertFalse("cover is never real", scheduler.tick());
		assertEquals(3, sent.size());
	}

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

package org.zerionproject.sync;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.NEVER;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.OPENING_WINDOW_INTERVAL_MS;

public class ZppOpeningWindowTest {

	private final AtomicLong now = new AtomicLong(1_000_000_000L);
	private final ZppConnectionRunnerImpl runner = new ZppConnectionRunnerImpl(
			new ZppRecordSink() {
				@Override
				public void deliver(int contactId, long sessionId, int type,
						byte[] payload) {
				}

				@Override
				public void onDisconnected(int contactId, long sessionId) {
				}
			}, new ZppConnectionRegistry() {
				@Override
				public void onConnectionOpened(int contactId,
						ZppSendScheduler scheduler, int maxRecordBytes) {
				}

				@Override
				public void onConnectionClosed(int contactId,
						ZppSendScheduler scheduler) {
				}
			}, new ZppPacingPolicy(), now::get);

	@Test
	public void theOpeningWindowIsGrantedOncePerContactPerInterval() {
		assertTrue("first connection", runner.grantsOpeningWindow(1, now.get()));
		now.addAndGet(5 * 60_000L);
		assertFalse("a reconnect within the interval",
				runner.grantsOpeningWindow(1, now.get()));
		assertTrue("another contact is independent",
				runner.grantsOpeningWindow(2, now.get()));
		now.addAndGet(OPENING_WINDOW_INTERVAL_MS - 5 * 60_000L - 1);
		assertFalse("just before the interval ends",
				runner.grantsOpeningWindow(1, now.get()));
		now.addAndGet(1);
		assertTrue("after the interval", runner.grantsOpeningWindow(1, now.get()));
		now.addAndGet(1);
		assertFalse("the grant started a new interval",
				runner.grantsOpeningWindow(1, now.get()));
	}

	@Test
	public void aClockThatWentBackGrantsTheWindowAgain() {
		assertTrue(runner.grantsOpeningWindow(1, now.get()));
		assertTrue(runner.grantsOpeningWindow(1, now.get() - 60_000L));
	}

	@Test
	public void theReceiveRationIsSharedByTheConnectionsOfAContact() {
		ZppConnectionRunnerImpl.ReceiveActivityGate first = runner.gateFor(1);
		assertSame("a later connection to the contact finds the same gate",
				first, runner.gateFor(1));
		assertNotSame(first, runner.gateFor(2));
		assertTrue("the first receipt activates once", first.admitReceipt());
		now.addAndGet(60_000L);
		assertFalse("a reconnect does not refill the ration",
				runner.gateFor(1).admitReceipt());
		now.addAndGet(ZppConnectionRunnerImpl.RECEIVE_ACTIVATION_INTERVAL_MS);
		assertTrue(runner.gateFor(1).admitReceipt());
	}

	@Test
	public void aControlRecordShortensTheWaitWithoutOpeningTheWindow()
			throws Exception {
		ZppConnectionRunnerImpl.SlotClock clock =
				new ZppConnectionRunnerImpl.SlotClock(System::currentTimeMillis,
						NEVER);
		ZppSendScheduler scheduler = new ZppSendScheduler(b -> {
		}, () -> true);
		ZppConnectionRunnerImpl.wire(scheduler, clock);
		AtomicBoolean running = new AtomicBoolean(true);
		long sentAt = System.currentTimeMillis();
		AtomicLong elapsed = new AtomicLong(-1);
		Thread waiter = new Thread(() -> {
			try {
				clock.awaitNextSlot(running, sentAt, 60_000L, 200L);
				elapsed.set(System.currentTimeMillis() - sentAt);
			} catch (InterruptedException ignored) {
			}
		});
		waiter.start();
		try {
			long deadline = System.currentTimeMillis() + 15_000;
			while (waiter.getState() != Thread.State.TIMED_WAITING
					&& waiter.getState() != Thread.State.TERMINATED) {
				assertTrue(System.currentTimeMillis() < deadline);
				Thread.sleep(1);
			}
			scheduler.enqueueControl(new byte[] {0x7F, 1, 2});
			waiter.join(30_000);
			assertFalse("the wait was not shortened", waiter.isAlive());
		} finally {
			running.set(false);
			waiter.interrupt();
		}
		assertTrue("the next frame comes at the active spacing",
				elapsed.get() >= 0 && elapsed.get() < 60_000L);
		assertEquals("a control record opens no window", NEVER,
				clock.lastRealMs);
		assertTrue("while it is queued the stream is active",
				ZppConnectionRunnerImpl.isActive(scheduler.getQueueDepth(),
						clock.lastRealMs, System.currentTimeMillis(),
						120_000L));
		assertTrue(scheduler.tick());
		assertFalse("once it has left the stream is idle again",
				ZppConnectionRunnerImpl.isActive(scheduler.getQueueDepth(),
						clock.lastRealMs, System.currentTimeMillis(),
						120_000L));
	}

	@Test
	public void onlyAFrameThisSideWroteOrAnAdmittedReceiptOpensTheWindow() {
		ZppConnectionRunnerImpl.SlotClock clock =
				new ZppConnectionRunnerImpl.SlotClock(now::get, NEVER);
		ZppConnectionRunnerImpl.ReceiveActivityGate gate = runner.gateFor(1);
		ZppConnectionRunnerImpl.afterFrame(clock, gate, true, false,
				now.get());
		assertEquals("a provoked reply opens no window", NEVER,
				clock.lastRealMs);
		ZppConnectionRunnerImpl.afterFrame(clock, gate, false, false,
				now.get());
		assertEquals("cover opens no window", NEVER, clock.lastRealMs);
		ZppConnectionRunnerImpl.afterFrame(clock, gate, true, true,
				now.get());
		assertEquals("a message this side wrote does", now.get(),
				clock.lastRealMs);
		now.addAndGet(60_000L);
		assertTrue("a receipt within the reply window is admitted",
				gate.admitReceipt());
		clock.noteActivity();
		assertEquals(now.get(), clock.lastRealMs);
	}

	@Test
	public void aConnectionWithoutTheWindowStartsIdleUntilSomethingIsQueued() {
		long idleAfter = new ZppPacingPolicy().idleAfterMs();
		assertFalse(ZppConnectionRunnerImpl.isActive(0, NEVER, now.get(),
				idleAfter));
		assertTrue("queued records always run at the active rate",
				ZppConnectionRunnerImpl.isActive(1, NEVER, now.get(),
						idleAfter));
		assertTrue(ZppConnectionRunnerImpl.isActive(0, now.get(),
				now.get() + idleAfter - 1, idleAfter));
		assertFalse(ZppConnectionRunnerImpl.isActive(0, now.get(),
				now.get() + idleAfter, idleAfter));
		assertFalse("a test pacing with no idle threshold does not overflow",
				ZppConnectionRunnerImpl.isActive(0, NEVER, now.get(),
						Long.MAX_VALUE));
		assertTrue(ZppConnectionRunnerImpl.isActive(0, now.get(), now.get(),
				Long.MAX_VALUE));
	}
}

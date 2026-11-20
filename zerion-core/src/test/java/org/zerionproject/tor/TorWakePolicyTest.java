package org.zerionproject.tor;

import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.tor.TorActivitySink.Activity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.tor.TorWakePolicy.BOOTSTRAP_GRACE_MS;
import static org.zerionproject.tor.TorWakePolicy.BOOTSTRAP_HOLD_CAP_MS;
import static org.zerionproject.tor.TorWakePolicy.CONNECTION_HOLD_MS;
import static org.zerionproject.tor.TorWakePolicy.DIAL_HOLD_MS;
import static org.zerionproject.tor.TorWakePolicy.HEARTBEAT_HOLD_MS;
import static org.zerionproject.tor.TorWakePolicy.HEARTBEAT_INTERVAL_MS;
import static org.zerionproject.tor.TorWakePolicy.NETWORK_ENABLED_HOLD_MS;
import static org.zerionproject.tor.TorWakePolicy.PUBLISH_BURST_CEILING_MS;
import static org.zerionproject.tor.TorWakePolicy.PUBLISH_CAP_MS;
import static org.zerionproject.tor.TorWakePolicy.CIRCUIT_EVENT_HOLD_MS;
import static org.zerionproject.tor.TorWakePolicy.TOR_EVENT_HOLD_MS;

public class TorWakePolicyTest {

	private static final String ONION =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	private final AtomicLong now = new AtomicLong(1_000_000);
	private final FakeScheduler scheduler = new FakeScheduler();
	private final FakeLock lock = new FakeLock();
	private final TorWakePolicy policy =
			new TorWakePolicy(scheduler, Runnable::run, now::get);

	public TorWakePolicyTest() {
		policy.attachLock(lock);
	}

	private void advance(long ms) {
		scheduler.advanceTo(now.get() + ms);
	}

	/**
	 * Brings the policy to the released steady state: network enabled,
	 * bootstrapped, every window expired.
	 */
	private void settle() {
		policy.onStarting();
		policy.onStartFinished();
		policy.onNetworkEnabled(true);
		policy.onBootstrapProgress(100);
		advance(NETWORK_ENABLED_HOLD_MS + BOOTSTRAP_GRACE_MS);
		assertFalse(lock.held);
	}

	@Test
	public void heldWhileStartingAndShortlyAfter() {
		assertFalse(lock.held);
		policy.onStarting();
		assertTrue(lock.held);
		policy.onStartFinished();
		assertTrue(lock.held);
		advance(TOR_EVENT_HOLD_MS - 1);
		assertTrue(lock.held);
		advance(1);
		assertFalse(lock.held);
		assertEquals(1, lock.acquires.get());
		assertEquals(1, lock.releases.get());
	}

	@Test
	public void heldFromNetworkEnableUntilBootstrapGraceEnds() {
		policy.onNetworkEnabled(true);
		assertTrue(lock.held);
		advance(10_000);
		policy.onBootstrapProgress(50);
		advance(NETWORK_ENABLED_HOLD_MS);
		assertTrue("still bootstrapping", lock.held);
		policy.onBootstrapProgress(100);
		assertTrue(lock.held);
		advance(BOOTSTRAP_GRACE_MS - 1);
		assertTrue(lock.held);
		advance(1);
		assertFalse(lock.held);
		assertEquals(1, lock.acquires.get());
		assertEquals(1, lock.releases.get());
	}

	@Test
	public void aBootstrapThatNeverFinishesIsReleasedAtTheCap() {
		policy.onNetworkEnabled(true);
		policy.onBootstrapProgress(10);
		advance(BOOTSTRAP_HOLD_CAP_MS - 1);
		assertTrue(lock.held);
		advance(1);
		assertFalse(lock.held);
	}

	@Test
	public void aTorEventReacquiresAndReleases30sLater() {
		settle();
		policy.onTorEvent();
		assertTrue(lock.held);
		advance(TOR_EVENT_HOLD_MS - 1);
		assertTrue(lock.held);
		policy.onTorEvent();
		advance(TOR_EVENT_HOLD_MS - 1);
		assertTrue("each event re-arms the window", lock.held);
		advance(1);
		assertFalse(lock.held);
		assertEquals(2, lock.acquires.get());
		assertEquals(2, lock.releases.get());
	}

	@Test
	public void aCircuitEventHoldsForFiveSecondsOnly() {
		settle();
		policy.onCircuitEvent();
		assertTrue(lock.held);
		advance(CIRCUIT_EVENT_HOLD_MS - 1);
		assertTrue(lock.held);
		policy.onCircuitEvent();
		advance(CIRCUIT_EVENT_HOLD_MS - 1);
		assertTrue("each circuit event re-arms the short window", lock.held);
		advance(1);
		assertFalse(lock.held);
		assertTrue(CIRCUIT_EVENT_HOLD_MS < TOR_EVENT_HOLD_MS);
		assertEquals(2, lock.acquires.get());
		assertEquals(2, lock.releases.get());
	}

	@Test
	public void connectionActivityHoldsFor60sAfterTheLastByte() {
		settle();
		policy.noteActivity(Activity.CONNECTION_DATA);
		assertTrue(lock.held);
		advance(CONNECTION_HOLD_MS / 2);
		policy.noteActivity(Activity.CONNECTION_DATA);
		advance(CONNECTION_HOLD_MS - 1);
		assertTrue(lock.held);
		advance(1);
		assertFalse(lock.held);
	}

	@Test
	public void aPublishHoldsUntilEveryUploadFinished() {
		settle();
		policy.onDescriptorUpload(ONION);
		policy.onDescriptorUpload(ONION);
		assertTrue(lock.held);
		advance(10_000);
		policy.onDescriptorUploadFinished(ONION);
		assertTrue("one upload still in flight", lock.held);
		policy.onDescriptorUploadFinished(ONION);
		assertFalse(lock.held);
	}

	@Test
	public void aPublishThatNeverFinishesIsReleasedAtTheCap() {
		settle();
		policy.onDescriptorUpload(ONION);
		advance(PUBLISH_CAP_MS - 1);
		assertTrue(lock.held);
		advance(1);
		assertFalse(lock.held);
		policy.onDescriptorUpload(ONION);
		assertTrue("a new publish starts a new cap", lock.held);
	}

	@Test
	public void anUnknownOnionFinishingDoesNotRelease() {
		settle();
		policy.onDescriptorUpload(ONION);
		policy.onDescriptorUploadFinished("b" + ONION.substring(1));
		assertTrue(lock.held);
	}

	@Test
	public void keepAwakeHoldsPermanentlyAndOffReleases() {
		settle();
		policy.setKeepAwake(true);
		assertTrue(lock.held);
		advance(TimeUnit.HOURS.toMillis(5));
		assertTrue(lock.held);
		policy.setKeepAwake(false);
		assertFalse(lock.held);
		assertEquals(lock.acquires.get(), lock.releases.get());
	}

	@Test
	public void keepAwakeDoesNotHoldWhileTheNetworkIsDisabled() {
		settle();
		policy.setKeepAwake(true);
		policy.onNetworkEnabled(false);
		assertFalse(lock.held);
		policy.onNetworkEnabled(true);
		assertTrue(lock.held);
	}

	@Test
	public void foregroundAndCallHoldWhileTheyLast() {
		settle();
		policy.setForeground(true);
		assertTrue(lock.held);
		advance(TimeUnit.HOURS.toMillis(1));
		assertTrue(lock.held);
		policy.setForeground(false);
		assertFalse(lock.held);
		policy.setCallInProgress(true);
		assertTrue(lock.held);
		advance(TimeUnit.HOURS.toMillis(1));
		policy.setCallInProgress(false);
		assertFalse(lock.held);
	}

	@Test
	public void heartbeatAcquiresForTheBoundedWindow() {
		policy.onNetworkEnabled(true);
		long enabledAt = now.get();
		policy.onBootstrapProgress(100);
		advance(NETWORK_ENABLED_HOLD_MS + BOOTSTRAP_GRACE_MS);
		assertFalse(lock.held);
		scheduler.advanceTo(enabledAt + HEARTBEAT_INTERVAL_MS - 1);
		assertFalse(lock.held);
		scheduler.advanceTo(enabledAt + HEARTBEAT_INTERVAL_MS);
		assertTrue(lock.held);
		advance(HEARTBEAT_HOLD_MS - 1);
		assertTrue(lock.held);
		advance(1);
		assertFalse(lock.held);
		scheduler.advanceTo(enabledAt + 2 * HEARTBEAT_INTERVAL_MS);
		assertTrue("the heartbeat repeats", lock.held);
		advance(HEARTBEAT_HOLD_MS);
		assertFalse(lock.held);
	}

	@Test
	public void torActivityDuringTheHeartbeatKeepsTheLockForTheBurst() {
		policy.onNetworkEnabled(true);
		long enabledAt = now.get();
		policy.onBootstrapProgress(100);
		scheduler.advanceTo(enabledAt + HEARTBEAT_INTERVAL_MS);
		assertTrue(lock.held);
		advance(2_000);
		policy.onTorEvent();
		advance(HEARTBEAT_HOLD_MS);
		assertTrue(lock.held);
		advance(TOR_EVENT_HOLD_MS - HEARTBEAT_HOLD_MS);
		assertFalse(lock.held);
	}

	@Test
	public void disablingTheNetworkReleasesAndStopsTheHeartbeat() {
		settle();
		policy.setForeground(true);
		policy.onTorEvent();
		policy.noteActivity(Activity.SYNC_PENDING);
		assertTrue(lock.held);
		policy.onNetworkEnabled(false);
		assertFalse(lock.held);
		advance(3 * HEARTBEAT_INTERVAL_MS);
		assertFalse("no heartbeat while disabled", lock.held);
		assertEquals(lock.acquires.get(), lock.releases.get());
	}

	@Test
	public void stoppingHoldsAndStoppedReleases() {
		settle();
		policy.setForeground(true);
		policy.onStopping();
		assertTrue(lock.held);
		policy.onStopped();
		assertFalse("stopped releases even with holders set", lock.held);
		policy.onTorEvent();
		assertFalse("no network after stop, so no hold", lock.held);
		advance(3 * HEARTBEAT_INTERVAL_MS);
		assertFalse(lock.held);
	}

	@Test
	public void everyAcquireIsBalancedByARelease() {
		policy.onStarting();
		policy.onStartFinished();
		policy.onNetworkEnabled(true);
		policy.onBootstrapProgress(30);
		policy.onDescriptorUpload(ONION);
		advance(20_000);
		policy.onBootstrapProgress(100);
		policy.onTorEvent();
		advance(5 * 60_000);
		policy.noteActivity(Activity.CONNECTION_ATTEMPT);
		policy.setForeground(true);
		policy.setForeground(false);
		advance(15 * 60_000);
		policy.onTorEvent();
		policy.onNetworkEnabled(false);
		policy.onNetworkEnabled(true);
		advance(25 * 60_000);
		policy.onStopping();
		policy.onStopped();
		assertFalse(lock.held);
		assertTrue(lock.acquires.get() > 1);
		assertEquals(lock.acquires.get(), lock.releases.get());
		assertEquals(0, lock.depth());
		assertEquals(1, lock.maxDepth());
	}

	@Test
	public void aLockAttachedWhileHeldIsAcquiredAtOnce() {
		TorWakePolicy late =
				new TorWakePolicy(scheduler, Runnable::run, now::get);
		late.onStarting();
		FakeLock lateLock = new FakeLock();
		late.attachLock(lateLock);
		assertTrue(lateLock.held);
		late.onStopped();
		assertFalse(lateLock.held);
	}

	@Test
	public void concurrentEventsDoNotDoubleAcquire() throws Exception {
		settle();
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		try {
			for (int i = 0; i < threads; i++) {
				int id = i;
				pool.execute(() -> {
					try {
						start.await();
						for (int j = 0; j < 500; j++) {
							if ((id + j) % 3 == 0) policy.onTorEvent();
							else if ((id + j) % 3 == 1) {
								policy.noteActivity(Activity.CONNECTION_DATA);
							} else policy.onDescriptorUpload(ONION + id);
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertTrue(done.await(30, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
		assertTrue(lock.held);
		assertEquals("one acquire for settling, one for the burst", 2,
				lock.acquires.get());
		assertEquals(1, lock.releases.get());
		assertEquals(1, lock.maxDepth());
		policy.onNetworkEnabled(false);
		assertFalse(lock.held);
		assertEquals(2, lock.releases.get());
	}

	@Test
	public void aDialHoldsFor15sOnly() {
		settle();
		policy.noteActivity(Activity.CONNECTION_ATTEMPT);
		assertTrue(lock.held);
		advance(DIAL_HOLD_MS - 1);
		assertTrue(lock.held);
		advance(1);
		assertFalse(lock.held);
		policy.noteActivity(Activity.CONNECTION_ATTEMPT);
		policy.noteActivity(Activity.SYNC_PENDING);
		advance(DIAL_HOLD_MS);
		assertTrue("queued records hold for the full 60 s", lock.held);
		advance(CONNECTION_HOLD_MS - DIAL_HOLD_MS);
		assertFalse(lock.held);
	}

	@Test
	public void anAcquireThatThrowsLeavesHeldFalseAndTheNextEventRetries() {
		settle();
		lock.failNextAcquires = 1;
		policy.onTorEvent();
		assertFalse("the failed acquire is not recorded as held",
				policy.isHeld());
		assertFalse(lock.held);
		assertEquals(1, lock.failedAcquires);
		policy.onTorEvent();
		assertTrue(policy.isHeld());
		assertTrue(lock.held);
		advance(TOR_EVENT_HOLD_MS);
		assertFalse(lock.held);
		assertEquals(lock.acquires.get(), lock.releases.get());
	}

	@Test
	public void aSecondAttachLockReplacesAndRebalances() {
		settle();
		policy.setForeground(true);
		assertTrue(lock.held);
		FakeLock replacement = new FakeLock();
		policy.attachLock(replacement);
		assertFalse("the old lock was released", lock.held);
		assertTrue("the new lock was acquired", replacement.held);
		assertEquals(lock.acquires.get(), lock.releases.get());
		policy.setForeground(false);
		assertFalse(replacement.held);
		assertEquals(1, replacement.acquires.get());
		assertEquals(1, replacement.releases.get());
		FakeLock third = new FakeLock();
		policy.attachLock(third);
		assertFalse("attaching while released acquires nothing", third.held);
		assertEquals(0, third.acquires.get());
	}

	@Test
	public void aCheckThatFiresEarlyReschedulesOnceWithoutLooping() {
		settle();
		policy.onTorEvent();
		assertEquals(1, scheduler.pendingOneShots());
		scheduler.fireEarliestWithoutAdvancing();
		assertTrue("still inside the window", lock.held);
		assertEquals("one reschedule", 1, scheduler.pendingOneShots());
		scheduler.fireEarliestWithoutAdvancing();
		assertEquals(1, scheduler.pendingOneShots());
		advance(TOR_EVENT_HOLD_MS);
		assertFalse(lock.held);
		assertEquals(0, scheduler.pendingOneShots());
	}

	@Test
	public void enablingTheNetworkTwiceKeepsTheWindowAndOneHeartbeat() {
		assertTrue(policy.onNetworkEnabled(true));
		policy.onBootstrapProgress(100);
		advance(60_000);
		assertFalse("already enabled", policy.onNetworkEnabled(true));
		assertEquals(1, scheduler.recurring());
		advance(NETWORK_ENABLED_HOLD_MS - 60_000);
		assertFalse("the 90 s window was not restarted", lock.held);
		policy.onNetworkEnabled(false);
		assertTrue(policy.onNetworkEnabled(true));
		assertTrue(lock.held);
		assertEquals(1, scheduler.recurring());
	}

	@Test
	public void theHeartbeatIsSkippedWhileHeldAndResumesAfterwards() {
		policy.onNetworkEnabled(true);
		long enabledAt = now.get();
		policy.onBootstrapProgress(100);
		advance(NETWORK_ENABLED_HOLD_MS + BOOTSTRAP_GRACE_MS);
		policy.setForeground(true);
		int acquires = lock.acquires.get();
		scheduler.advanceTo(enabledAt + HEARTBEAT_INTERVAL_MS + 1);
		assertEquals("no extra acquire while already held", acquires,
				lock.acquires.get());
		policy.setForeground(false);
		assertFalse(lock.held);
		scheduler.advanceTo(enabledAt + 2 * HEARTBEAT_INTERVAL_MS);
		assertTrue("the heartbeat resumed", lock.held);
		advance(HEARTBEAT_HOLD_MS);
		assertFalse(lock.held);
	}

	@Test
	public void aStaleCheckAfterStopIsANoOp() {
		settle();
		policy.onTorEvent();
		assertTrue(lock.held);
		policy.onStopping();
		policy.onStopped();
		assertFalse(lock.held);
		int acquires = lock.acquires.get();
		advance(TOR_EVENT_HOLD_MS + 1);
		assertFalse(lock.held);
		assertEquals(acquires, lock.acquires.get());
		assertEquals(lock.acquires.get(), lock.releases.get());
	}

	@Test
	public void thePublishCapExtendsPerUploadUpToTheCeiling() {
		settle();
		long start = now.get();
		policy.onDescriptorUpload(ONION);
		advance(100_000);
		policy.onDescriptorUpload(ONION);
		advance(PUBLISH_CAP_MS - 1);
		assertTrue("the second upload extended the cap", lock.held);
		policy.onDescriptorUpload(ONION);
		scheduler.advanceTo(start + PUBLISH_BURST_CEILING_MS - 1);
		assertTrue(lock.held);
		advance(1);
		assertFalse("the burst ceiling released it", lock.held);
		policy.onDescriptorUpload(ONION);
		assertTrue("a new burst starts after the ceiling", lock.held);
		policy.onDescriptorUploadFinished(ONION);
		assertFalse(lock.held);
	}

	@Test
	public void quietMeansNoPersonNoCallNoKeepAwakeAndNoConnectionData() {
		assertFalse("network disabled", policy.isQuiet());
		policy.onStarting();
		assertFalse(policy.isQuiet());
		policy.onStartFinished();
		policy.onNetworkEnabled(true);
		policy.onBootstrapProgress(100);
		assertTrue("bootstrap and the enable window do not count",
				policy.isQuiet());
		assertTrue("quiet even while the lock is held", lock.held);
		policy.setForeground(true);
		assertFalse("the foreground is not quiet", policy.isQuiet());
		policy.setForeground(false);
		assertTrue(policy.isQuiet());
		policy.setCallInProgress(true);
		assertFalse(policy.isQuiet());
		policy.setCallInProgress(false);
		policy.setKeepAwake(true);
		assertFalse(policy.isQuiet());
		policy.setKeepAwake(false);
		assertTrue(policy.isQuiet());
		policy.noteActivity(Activity.CONNECTION_ATTEMPT);
		policy.onTorEvent();
		policy.onDescriptorUpload(ONION);
		assertTrue("a dial and Tor's own events do not count",
				policy.isQuiet());
		policy.noteActivity(Activity.CONNECTION_DATA);
		assertFalse("connection data counts", policy.isQuiet());
		advance(CONNECTION_HOLD_MS);
		assertTrue(policy.isQuiet());
		policy.onStopping();
		assertFalse(policy.isQuiet());
		policy.onStopped();
		assertFalse("no network after stop", policy.isQuiet());
	}

	private static class FakeLock implements TorWakePolicy.Lock {

		final AtomicInteger acquires = new AtomicInteger();
		final AtomicInteger releases = new AtomicInteger();
		volatile boolean held = false;
		volatile int failNextAcquires = 0;
		volatile int failedAcquires = 0;
		private int depth = 0;
		private int max = 0;

		@Override
		public synchronized void acquire() {
			if (failNextAcquires > 0) {
				failNextAcquires--;
				failedAcquires++;
				throw new IllegalStateException("acquire refused");
			}
			acquires.incrementAndGet();
			depth++;
			if (depth > max) max = depth;
			held = true;
		}

		@Override
		public synchronized void release() {
			releases.incrementAndGet();
			depth--;
			if (depth < 0) throw new AssertionError("release without acquire");
			held = depth > 0;
		}

		synchronized int depth() {
			return depth;
		}

		synchronized int maxDepth() {
			return max;
		}
	}

	private final class FakeScheduler implements TaskScheduler {

		private final List<Due> tasks = new ArrayList<>();

		@Override
		public synchronized Cancellable schedule(Runnable task,
				Executor executor, long delay, TimeUnit unit) {
			Due d = new Due(task, executor,
					now.get() + MILLISECONDS.convert(delay, unit), 0);
			tasks.add(d);
			return d;
		}

		@Override
		public synchronized Cancellable scheduleWithFixedDelay(Runnable task,
				Executor executor, long delay, long interval, TimeUnit unit) {
			Due d = new Due(task, executor,
					now.get() + MILLISECONDS.convert(delay, unit),
					MILLISECONDS.convert(interval, unit));
			tasks.add(d);
			return d;
		}

		synchronized int pendingOneShots() {
			int n = 0;
			for (Due d : tasks) if (!d.cancelled.get() && d.interval == 0) n++;
			return n;
		}

		synchronized int recurring() {
			int n = 0;
			for (Due d : tasks) if (!d.cancelled.get() && d.interval > 0) n++;
			return n;
		}

		void fireEarliestWithoutAdvancing() {
			Due next = null;
			synchronized (this) {
				for (Due d : tasks) {
					if (d.cancelled.get() || d.interval > 0) continue;
					if (next == null || d.dueAt < next.dueAt) next = d;
				}
				if (next != null) tasks.remove(next);
			}
			if (next != null) next.executor.execute(next.task);
		}

		void advanceTo(long target) {
			while (true) {
				Due next = null;
				synchronized (this) {
					for (Due d : tasks) {
						if (d.cancelled.get()) continue;
						if (d.dueAt > target) continue;
						if (next == null || d.dueAt < next.dueAt) next = d;
					}
					if (next != null) tasks.remove(next);
				}
				if (next == null) break;
				if (next.dueAt > now.get()) now.set(next.dueAt);
				next.executor.execute(next.task);
				if (next.interval > 0 && !next.cancelled.get()) {
					synchronized (this) {
						tasks.add(new Due(next.task, next.executor,
								next.dueAt + next.interval, next.interval,
								next.cancelled));
					}
				}
			}
			if (target > now.get()) now.set(target);
		}

		private final class Due implements Cancellable {

			final Runnable task;
			final Executor executor;
			final long dueAt;
			final long interval;
			final AtomicBoolean cancelled;

			Due(Runnable task, Executor executor, long dueAt, long interval) {
				this(task, executor, dueAt, interval, new AtomicBoolean());
			}

			Due(Runnable task, Executor executor, long dueAt, long interval,
					AtomicBoolean cancelled) {
				this.task = task;
				this.executor = executor;
				this.dueAt = dueAt;
				this.interval = interval;
				this.cancelled = cancelled;
			}

			@Override
			public void cancel() {
				cancelled.set(true);
			}
		}
	}
}

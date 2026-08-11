package org.zerionproject.wire;

import org.junit.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.zerionproject.wire.ZwfConstants.DIRECTION_RECV;
import static org.zerionproject.wire.ZwfConstants.DIRECTION_SEND;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The #1 safety gate for the Zerion 3.0 wire format: proves a stream id is never
 * reused, even across simulated crashes/restarts and under concurrency.
 */
public class ZwfStreamCounterTest {

	/**
	 * In-memory {@link StreamCounterStore} whose {@code storeHighWater} is
	 * treated as durable. A "crash" is modelled by discarding the
	 * {@link ZwfStreamCounter} (its cache) while keeping this store, exactly as
	 * a real restart would reload the high-water mark from the database.
	 */
	private static class DurableInMemoryStore implements StreamCounterStore {
		private final Map<Long, Long> persisted = new ConcurrentHashMap<>();

		@Override
		public long loadHighWater(int contactId, int direction) {
			Long v = persisted.get(key(contactId, direction));
			return v == null ? 0 : v;
		}

		@Override
		public void storeHighWater(int contactId, int direction,
				long highWater) {
			persisted.put(key(contactId, direction), highWater);
		}

		private static long key(int contactId, int direction) {
			return (((long) contactId) << 1) | (direction & 1L);
		}
	}

	private static final int CONTACT_A = 473729761;
	private static final int CONTACT_B = 12;

	@Test
	public void sendIdsAreStrictlyMonotonicFromOne() {
		ZwfStreamCounter counter = new ZwfStreamCounter(new DurableInMemoryStore());
		long prev = 0;
		for (int i = 1; i <= 5000; i++) {
			long id = counter.allocateSendStreamId(CONTACT_A);
			assertEquals(i, id);
			assertTrue(id > prev);
			prev = id;
		}
	}

	@Test
	public void sendIdsPerContactAreIndependent() {
		ZwfStreamCounter counter = new ZwfStreamCounter(new DurableInMemoryStore());
		assertEquals(1, counter.allocateSendStreamId(CONTACT_A));
		assertEquals(1, counter.allocateSendStreamId(CONTACT_B));
		assertEquals(2, counter.allocateSendStreamId(CONTACT_A));
		assertEquals(2, counter.allocateSendStreamId(CONTACT_B));
		assertEquals(3, counter.allocateSendStreamId(CONTACT_A));
	}

	@Test
	public void sendIdsNeverRepeatAcrossRestarts() {
		DurableInMemoryStore store = new DurableInMemoryStore();
		Set<Long> seen = ConcurrentHashMap.newKeySet();
		long highest = 0;
		// Simulate 50 crash/restart cycles, allocating a handful each time.
		for (int cycle = 0; cycle < 50; cycle++) {
			ZwfStreamCounter counter = new ZwfStreamCounter(store); // fresh cache
			for (int i = 0; i < 7; i++) {
				long id = counter.allocateSendStreamId(CONTACT_A);
				assertTrue("id must be fresh", seen.add(id));
				assertTrue("id must strictly increase across restarts",
						id > highest);
				highest = id;
			}
		}
		assertEquals(50 * 7, highest);
	}

	@Test
	public void recvRejectsReplayButAcceptsReorder() {
		ZwfStreamCounter counter = new ZwfStreamCounter(new DurableInMemoryStore());
		assertTrue(counter.acceptRecvStreamId(CONTACT_A, 1));
		assertTrue(counter.acceptRecvStreamId(CONTACT_A, 2));
		assertTrue(counter.acceptRecvStreamId(CONTACT_A, 3));
		// replay of an already-seen id is rejected
		assertFalse(counter.acceptRecvStreamId(CONTACT_A, 2));
		assertFalse(counter.acceptRecvStreamId(CONTACT_A, 3));
		// a jump forward advances the high-water
		assertTrue(counter.acceptRecvStreamId(CONTACT_A, 10));
		assertFalse(counter.acceptRecvStreamId(CONTACT_A, 10)); // replay
		// reorder: ids below the high-water but inside the window are accepted
		assertTrue(counter.acceptRecvStreamId(CONTACT_A, 5));
		assertTrue(counter.acceptRecvStreamId(CONTACT_A, 9));
		assertFalse(counter.acceptRecvStreamId(CONTACT_A, 5)); // now seen
		assertTrue(counter.acceptRecvStreamId(CONTACT_A, 11));
		// zero / negative are always rejected
		assertFalse(counter.acceptRecvStreamId(CONTACT_A, 0));
		assertFalse(counter.acceptRecvStreamId(CONTACT_A, -1));
	}

	@Test
	public void farAheadDeliveryDoesNotBrickButStaleIsRejected() {
		int w = REPLAY_WINDOW_SIZE;
		ZwfStreamCounter counter = new ZwfStreamCounter(new DurableInMemoryStore());
		// An early far-ahead id (natural churn or an on-path attacker) must not
		// slide the window past genuine lower streams still inside it.
		assertTrue(counter.acceptRecvStreamId(CONTACT_A, 2L * w));
		assertTrue(counter.acceptRecvStreamId(CONTACT_A, 2L * w - 1)); // within
		assertTrue(counter.acceptRecvStreamId(CONTACT_A, w + 1));      // within
		// more than a window below the high-water = stale
		assertFalse(counter.acceptRecvStreamId(CONTACT_A, w));         // == hw-w
		assertFalse(counter.acceptRecvStreamId(CONTACT_A, 1));
	}

	@Test
	public void recvHighWaterSurvivesRestart() {
		int w = REPLAY_WINDOW_SIZE;
		DurableInMemoryStore store = new DurableInMemoryStore();
		ZwfStreamCounter before = new ZwfStreamCounter(store);
		for (long id = 1; id <= 2L * w; id++) {
			assertTrue(before.acceptRecvStreamId(CONTACT_A, id));
		}
		// After restart the persistent high-water still bars anything older than
		// the window (the in-memory seen-set inside the window is intentionally
		// not persisted - a bounded duplicate window the message layer dedups).
		ZwfStreamCounter after = new ZwfStreamCounter(store);
		assertFalse("stale id below the window rejected after restart",
				after.acceptRecvStreamId(CONTACT_A, w / 2));
		assertTrue(after.acceptRecvStreamId(CONTACT_A, 2L * w + 1));
	}

	@Test
	public void concurrentSendAllocationsAreUniqueAndContiguous()
			throws Exception {
		ZwfStreamCounter counter = new ZwfStreamCounter(new DurableInMemoryStore());
		int threads = 16;
		int perThread = 2000;
		int total = threads * perThread;
		Set<Long> ids = ConcurrentHashMap.newKeySet();
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		for (int t = 0; t < threads; t++) {
			pool.execute(() -> {
				try {
					start.await();
					for (int i = 0; i < perThread; i++) {
						ids.add(counter.allocateSendStreamId(CONTACT_A));
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
		pool.shutdownNow();
		// No duplicates, and exactly the contiguous range 1..total was handed out.
		assertEquals(total, ids.size());
		assertTrue(ids.contains(1L));
		assertTrue(ids.contains((long) total));
		assertFalse(ids.contains((long) total + 1));
	}
}

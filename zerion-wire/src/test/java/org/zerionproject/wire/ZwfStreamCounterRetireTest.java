package org.zerionproject.wire;

import org.junit.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.zerionproject.wire.ZwfConstants.DIRECTION_RECV;
import static org.zerionproject.wire.ZwfConstants.DIRECTION_SEND;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZwfStreamCounterRetireTest {

	private static final int CONTACT = 2;
	private static final int OTHER = 3;

	private static class Store implements StreamCounterStore {
		final Map<Long, Long> persisted = new ConcurrentHashMap<>();

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

		void clear(int contactId) {
			persisted.remove(key(contactId, DIRECTION_SEND));
			persisted.remove(key(contactId, DIRECTION_RECV));
		}

		static long key(int contactId, int direction) {
			return (((long) contactId) << 1) | (direction & 1L);
		}
	}

	@Test
	public void anEarlierGenerationCanNeitherAllocateNorAccept() {
		Store store = new Store();
		ZwfStreamCounter counter = new ZwfStreamCounter(store);
		long before = counter.generation(CONTACT);
		assertEquals(1, counter.allocateSendStreamId(CONTACT, before));
		assertTrue(counter.acceptRecvStreamId(CONTACT, 7, before));
		counter.retireContact(CONTACT);
		store.clear(CONTACT);
		try {
			counter.allocateSendStreamId(CONTACT, before);
			fail("a retired generation took a send stream id");
		} catch (IllegalStateException expected) {
		}
		assertFalse(counter.acceptRecvStreamId(CONTACT, 8, before));
		assertTrue(store.persisted.isEmpty());
		long after = counter.generation(CONTACT);
		assertEquals(1, counter.allocateSendStreamId(CONTACT, after));
		assertTrue(counter.acceptRecvStreamId(CONTACT, 1, after));
		try {
			counter.allocateSendStreamId(CONTACT, before);
			fail("a retired generation took a send stream id");
		} catch (IllegalStateException expected) {
		}
	}

	@Test
	public void aRolledBackRemovalReusesNothing() {
		Store store = new Store();
		ZwfStreamCounter counter = new ZwfStreamCounter(store);
		for (int i = 1; i <= 40; i++) counter.allocateSendStreamId(CONTACT);
		for (long s = 1; s <= 2 * REPLAY_WINDOW_SIZE; s++) {
			assertTrue(counter.acceptRecvStreamId(CONTACT, s));
		}
		counter.retireContact(CONTACT);
		long now = counter.generation(CONTACT);
		assertEquals(41, counter.allocateSendStreamId(CONTACT, now));
		assertFalse(counter.acceptRecvStreamId(CONTACT, 2 * REPLAY_WINDOW_SIZE,
				now));
		assertFalse(counter.acceptRecvStreamId(CONTACT, 1, now));
		assertTrue(counter.acceptRecvStreamId(CONTACT,
				2 * REPLAY_WINDOW_SIZE + 1, now));
	}

	@Test
	public void retiringOneContactLeavesTheOthersAlone() {
		Store store = new Store();
		ZwfStreamCounter counter = new ZwfStreamCounter(store);
		long other = counter.generation(OTHER);
		assertEquals(1, counter.allocateSendStreamId(OTHER, other));
		assertTrue(counter.acceptRecvStreamId(OTHER, 5, other));
		counter.retireContact(CONTACT);
		store.clear(CONTACT);
		assertEquals(other, counter.generation(OTHER));
		assertEquals(2, counter.allocateSendStreamId(OTHER, other));
		assertFalse(counter.acceptRecvStreamId(OTHER, 5, other));
		assertEquals(5, counter.currentRecvHighWater(OTHER));
	}

	@Test(timeout = 10_000)
	public void retiringDoesNotWaitForAPersistInProgress() throws Exception {
		CountDownLatch storing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Store store = new Store() {
			@Override
			public void storeHighWater(int contactId, int direction,
					long highWater) {
				storing.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				super.storeHighWater(contactId, direction, highWater);
			}
		};
		ZwfStreamCounter counter = new ZwfStreamCounter(store);
		AtomicLong allocated = new AtomicLong();
		Thread t = new Thread(() ->
				allocated.set(counter.allocateSendStreamId(OTHER)));
		t.start();
		assertTrue(storing.await(5, TimeUnit.SECONDS));
		counter.retireContact(CONTACT);
		assertEquals(1, counter.generation(CONTACT));
		release.countDown();
		t.join();
		assertEquals(1, allocated.get());
	}

	private static class SerialisingStore extends Store {
		final CountDownLatch waiting = new CountDownLatch(1);
		final CountDownLatch removalDone = new CountDownLatch(1);

		@Override
		public boolean storeHighWaterIf(int contactId, int direction,
				long highWater, java.util.function.BooleanSupplier stillCurrent) {
			waiting.countDown();
			try {
				removalDone.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (!stillCurrent.getAsBoolean()) return false;
			storeHighWater(contactId, direction, highWater);
			return true;
		}
	}

	private static void removeWhileWriting(ZwfStreamCounter counter,
			SerialisingStore store, Runnable write) throws Exception {
		Thread t = new Thread(write);
		t.start();
		assertTrue(store.waiting.await(5, TimeUnit.SECONDS));
		counter.retireContact(CONTACT);
		store.clear(CONTACT);
		store.removalDone.countDown();
		t.join();
	}

	@Test
	public void aReceiveMarkWaitingForTheRemovalIsNotWrittenAfterIt()
			throws Exception {
		SerialisingStore store = new SerialisingStore();
		store.persisted.put(Store.key(CONTACT, DIRECTION_RECV), 5000L);
		ZwfStreamCounter counter = new ZwfStreamCounter(store);
		long before = counter.generation(CONTACT);
		java.util.concurrent.atomic.AtomicBoolean accepted =
				new java.util.concurrent.atomic.AtomicBoolean(true);
		removeWhileWriting(counter, store, () -> accepted.set(
				counter.acceptRecvStreamId(CONTACT, 5001, before)));
		assertFalse("the removed contact's stream is dropped",
				accepted.get());
		assertEquals("the cleared mark stays cleared", 0,
				store.loadHighWater(CONTACT, DIRECTION_RECV));
		assertTrue("the next contact with the id starts fresh",
				counter.acceptRecvStreamId(CONTACT, 1));
	}

	@Test
	public void aSendMarkWaitingForTheRemovalIsNotWrittenAfterIt()
			throws Exception {
		SerialisingStore store = new SerialisingStore();
		store.persisted.put(Store.key(CONTACT, DIRECTION_SEND), 70L);
		ZwfStreamCounter counter = new ZwfStreamCounter(store);
		long before = counter.generation(CONTACT);
		java.util.concurrent.atomic.AtomicReference<Throwable> failure =
				new java.util.concurrent.atomic.AtomicReference<>();
		removeWhileWriting(counter, store, () -> {
			try {
				counter.allocateSendStreamId(CONTACT, before);
			} catch (IllegalStateException e) {
				failure.set(e);
			}
		});
		assertTrue("the removed contact takes no send stream id",
				failure.get() != null);
		assertEquals(0, store.loadHighWater(CONTACT, DIRECTION_SEND));
		assertEquals(1, counter.allocateSendStreamId(CONTACT));
	}
}

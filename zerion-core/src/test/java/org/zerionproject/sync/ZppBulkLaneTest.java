package org.zerionproject.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.event.MessageRequestedEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmFragmenter;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static org.zerionproject.core.test.TestUtils.getRandomId;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.record.Record.RECORD_HEADER_BYTES;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_LENGTH;

public class ZppBulkLaneTest {

	private static final long NOW = 1_700_000_000_000L;
	private static final int FRAME_RECORD_BYTES = 1_600;
	private static final ContactId CONTACT = new ContactId(1);

	@Test
	public void smallRecordsOvertakeQueuedBulkFramesOneFramePerSlot()
			throws Exception {
		List<byte[]> sent = new ArrayList<>();
		ZppSendScheduler s = new ZppSendScheduler(sent::add, () -> true);
		for (int i = 0; i < 4; i++) {
			s.enqueueRecord(new byte[] {9, (byte) i}, true, true);
		}
		s.enqueueRecord(new byte[] {1}, false, false);
		s.enqueueRecord(new byte[] {2}, true, false);
		assertEquals(6, s.getQueueDepth());
		for (int i = 0; i < 6; i++) assertTrue(s.tick());
		assertEquals(1, sent.get(0)[0]);
		assertEquals(2, sent.get(1)[0]);
		for (int i = 2; i < 6; i++) {
			assertEquals(9, sent.get(i)[0]);
			assertEquals(i - 2, sent.get(i)[1]);
		}
		assertFalse("an empty queue sends cover", s.tick());
		assertEquals(7, sent.size());
	}

	@Test
	public void aFragmentedMessageTakesTheBulkLaneAndASmallOneDoesNot()
			throws Exception {
		List<String> lanes = new ArrayList<>();
		ZppSendScheduler scheduler = new ZppSendScheduler(r -> {
		}, () -> true) {
			@Override
			public void enqueueRecord(byte[] record, boolean userOriginated,
					boolean bulkLane, Runnable onSent) {
				lanes.add(bulkLane ? "bulk" : "small");
			}
		};
		ZppOutgoingSource source = new ZppOutgoingSource(null, Runnable::run,
				null, null, null, null,
				new org.zerionproject.core.api.contact.ContactId(1), 60_000,
				1_600, scheduler, new LocalMessageLog(() -> NOW));
		java.lang.reflect.Method enqueue =
				ZppOutgoingSource.class.getDeclaredMethod("enqueue",
						byte[].class, boolean.class);
		enqueue.setAccessible(true);
		enqueue.invoke(source, new byte[100], true);
		enqueue.invoke(source, new byte[5_000], true);
		assertEquals("small", lanes.get(0));
		assertEquals(5, lanes.size());
		assertEquals(Arrays.asList("bulk", "bulk", "bulk", "bulk"),
				lanes.subList(1, 5));
	}

	@Test
	public void theFirstTransmissionCountsHoweverOldAResendDoesNot() {
		GroupId g = new GroupId(getRandomId());
		org.zerionproject.core.api.sync.Message old =
				new org.zerionproject.core.api.sync.Message(
						new MessageId(getRandomId()), g,
						NOW - 15 * 60_000L, new byte[10]);
		LocalMessageLog localMessages = new LocalMessageLog(() -> NOW);
		localMessages.noteLocal(old.getId());
		ZppOutgoingSource source = new ZppOutgoingSource(null, Runnable::run,
				null, null, null, null,
				new org.zerionproject.core.api.contact.ContactId(1), 60_000,
				1_600, new ZppSendScheduler(r -> {
				}, () -> true), localMessages);
		assertTrue("the first send of own content counts",
				source.isFresh(old, NOW));
		assertFalse("a resend does not", source.isFresh(old, NOW));
	}

	@Test
	public void aMessageIsForgottenAfterADay() {
		long[] now = {NOW};
		LocalMessageLog log = new LocalMessageLog(() -> now[0]);
		MessageId id = new MessageId(getRandomId());
		log.noteLocal(id);
		now[0] = NOW + LocalMessageLog.FRESH_MESSAGE_MS + 1;
		assertFalse(log.takeFirstSend(id));
	}

	@Test
	public void aMessageWhoseFramesStillWaitIsNeitherOfferedNorQueuedAgain()
			throws Exception {
		GroupId g = new GroupId(getRandomId());
		Message big = new Message(new MessageId(getRandomId()), g, NOW,
				new byte[10_000]);
		Message other = new Message(new MessageId(getRandomId()), g, NOW,
				new byte[10]);
		ZmmSyncCodec codec = OversizedRecordTest.codec();
		AtomicInteger serves = new AtomicInteger(0);
		AtomicInteger offers = new AtomicInteger(0);
		DatabaseComponent db = db((name, args) -> {
			if (name.equals("generateRequestedBatch")) {
				return serves.getAndDecrement() > 0
						? singletonList(big) : null;
			}
			if (name.equals("generateOffer")) {
				return offers.getAndDecrement() > 0 ? new Offer(
						Arrays.asList(big.getId(), other.getId())) : null;
			}
			if (name.equals("getNextSendTime")) return Long.MAX_VALUE;
			return null;
		});
		List<byte[]> small = new ArrayList<>();
		ZppSendScheduler scheduler = new ZppSendScheduler(r -> {
		}, () -> true) {
			@Override
			public void enqueueRecord(byte[] record, boolean userOriginated,
					boolean bulkLane, Runnable onSent) {
				super.enqueueRecord(record, userOriginated, bulkLane, onSent);
				if (!bulkLane) small.add(record);
			}
		};
		ZppOutgoingSource source = source(db, codec, scheduler);

		serves.set(1);
		source.eventOccurred(new MessageRequestedEvent(CONTACT));
		int frames = scheduler.getBulkDepth();
		assertTrue("the message takes several bulk frames", frames > 1);
		assertTrue(serves.get() <= 0);

		serves.set(1);
		source.eventOccurred(new MessageRequestedEvent(CONTACT));
		assertEquals("a second request while the frames wait queues nothing",
				frames, scheduler.getBulkDepth());

		offers.set(1);
		source.start();
		assertEquals("one offer record left", 1, small.size());
		int oneId = ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				codec.encodeOffer(new Offer(singletonList(other.getId()))), 0,
				FRAME_RECORD_BYTES).get(0).length;
		int twoIds = ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				codec.encodeOffer(new Offer(Arrays.asList(big.getId(),
						other.getId()))), 0, FRAME_RECORD_BYTES).get(0).length;
		assertNotEquals(oneId, twoIds);
		assertEquals("the waiting message is left out of the offer", oneId,
				small.get(0).length);

		while (scheduler.getQueueDepth() > 0) scheduler.tick();
		serves.set(1);
		source.eventOccurred(new MessageRequestedEvent(CONTACT));
		assertEquals("once its frames have left it can be sent again",
				frames, scheduler.getBulkDepth());
	}

	@Test
	public void theBatchIsBoundedByTheRoomLeftInTheBulkLane()
			throws Exception {
		AtomicLong capacity = new AtomicLong(-1);
		DatabaseComponent db = db((name, args) -> {
			if (name.equals("generateRequestedBatch")) {
				capacity.set((Long) args[2]);
				return null;
			}
			if (name.equals("getNextSendTime")) return Long.MAX_VALUE;
			return null;
		});
		ZppSendScheduler scheduler = new ZppSendScheduler(r -> {
		}, () -> true);
		ZppOutgoingSource source =
				source(db, OversizedRecordTest.codec(), scheduler);

		source.eventOccurred(new MessageRequestedEvent(CONTACT));
		long batchCapacity = (RECORD_HEADER_BYTES + MAX_MESSAGE_LENGTH) * 2L;
		assertEquals("an empty lane allows the whole lane",
				Math.min(batchCapacity, 512L * FRAME_RECORD_BYTES),
				capacity.get());

		for (int i = 0; i < 512; i++) {
			scheduler.enqueueRecord(new byte[] {1}, false, true);
		}
		source.eventOccurred(new MessageRequestedEvent(CONTACT));
		assertEquals("a full lane still allows one frame",
				(long) FRAME_RECORD_BYTES, capacity.get());

		for (int i = 0; i < 100; i++) scheduler.tick();
		source.eventOccurred(new MessageRequestedEvent(CONTACT));
		assertEquals(100L * FRAME_RECORD_BYTES, capacity.get());
	}

	private static DatabaseComponent db(
			BiFunction<String, Object[], Object> handler) {
		return (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if (name.equals("transactionWithNullableResult")
							|| name.equals("transactionWithResult")) {
						java.lang.reflect.Method call = null;
						for (java.lang.reflect.Method mm :
								args[1].getClass().getMethods()) {
							if (mm.getName().equals("call")) call = mm;
						}
						if (call == null) throw new AssertionError();
						call.setAccessible(true);
						return call.invoke(args[1],
								new Transaction(null, false));
					}
					return handler.apply(name, args);
				});
	}

	private static ZppOutgoingSource source(DatabaseComponent db,
			ZmmSyncCodec codec, ZppSendScheduler scheduler) {
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return NOW;
			}

			@Override
			public void sleep(long milliseconds) {
			}
		};
		EventBus bus = (EventBus) Proxy.newProxyInstance(
				EventBus.class.getClassLoader(), new Class<?>[] {EventBus.class},
				(proxy, method, args) -> null);
		TaskScheduler tasks = (TaskScheduler) Proxy.newProxyInstance(
				TaskScheduler.class.getClassLoader(),
				new Class<?>[] {TaskScheduler.class},
				(proxy, method, args) -> null);
		return new ZppOutgoingSource(db, Runnable::run, bus, tasks, clock,
				codec, CONTACT, 60_000, FRAME_RECORD_BYTES, scheduler,
				new LocalMessageLog(() -> NOW));
	}
}

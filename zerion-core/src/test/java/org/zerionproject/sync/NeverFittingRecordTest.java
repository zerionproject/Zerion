package org.zerionproject.sync;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.event.MessageRequestedEvent;
import org.zerionproject.core.api.sync.event.MessageSharedEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.message.ZmmConstants;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.record.Record.RECORD_HEADER_BYTES;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_DELIVERABLE_MESSAGE_BODY_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_ONE_RECORD_MESSAGE_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_SYNC_RECORD_BYTES;
import static org.zerionproject.core.api.sync.SyncConstants.MESSAGE_HEADER_LENGTH;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class NeverFittingRecordTest {

	private static final long START = 1_700_000_000_000L;
	private static final int FRAME_RECORD_BYTES = 1_600;
	private static final int LARGEST_FITTING_BODY = 994_146;
	private static final long MINUTE = 60_000L;

	private static final class Task {
		private final long due;
		private final Runnable run;
		private boolean cancelled;

		private Task(long due, Runnable run) {
			this.due = due;
			this.run = run;
		}
	}

	private final ContactId contact = new ContactId(1);
	private final List<byte[]> queued =
			Collections.synchronizedList(new ArrayList<>());
	private final List<Runnable> sentCallbacks = new ArrayList<>();
	private final List<MessageId> unshared = new ArrayList<>();
	private final AtomicInteger offerIdsAsked = new AtomicInteger(-1);
	private final AtomicInteger batchPasses = new AtomicInteger();
	private final List<Message> toSend = new ArrayList<>();
	private final List<Offer> toOffer = new ArrayList<>();
	private final List<Task> tasks = new ArrayList<>();
	private long now = START;
	private int bulkDepth = 0;

	private ZppOutgoingSource source() throws Exception {
		ZppSendScheduler scheduler = new ZppSendScheduler(r -> {
		}, () -> true) {
			@Override
			public void enqueueRecord(byte[] record, boolean userOriginated,
					boolean opensWindow, int rank, boolean bulkLane,
					Runnable onSent) {
				queued.add(record);
				sentCallbacks.add(onSent);
			}

			@Override
			public int getBulkDepth() {
				return bulkDepth;
			}
		};
		DatabaseComponent db = (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if (name.equals("transactionWithNullableResult")
							|| name.equals("transactionWithResult")) {
						return run(args[1], "call");
					}
					if (name.equals("transaction")) {
						run(args[1], "run");
						return null;
					}
					if (name.equals("generateRequestedBatch")) {
						batchPasses.incrementAndGet();
						return batch((Long) args[2]);
					}
					if (name.equals("generateOffer")) {
						offerIdsAsked.set((Integer) args[2]);
						return toOffer.isEmpty() ? null : toOffer.remove(0);
					}
					if (name.equals("setMessageNotShared")) {
						unshared.add((MessageId) args[1]);
						return null;
					}
					if (name.equals("getNextSendTime")) return nextSendTime();
					return null;
				});
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return now;
			}

			@Override
			public void sleep(long milliseconds) {
			}
		};
		EventBus bus = (EventBus) Proxy.newProxyInstance(
				EventBus.class.getClassLoader(), new Class<?>[] {EventBus.class},
				(proxy, method, args) -> null);
		TaskScheduler taskScheduler = new TaskScheduler() {
			@Override
			public Cancellable schedule(Runnable task, Executor executor,
					long delay, TimeUnit unit) {
				Task t = new Task(now + unit.toMillis(delay),
						() -> executor.execute(task));
				tasks.add(t);
				return () -> t.cancelled = true;
			}

			@Override
			public Cancellable scheduleWithFixedDelay(Runnable task,
					Executor executor, long delay, long interval,
					TimeUnit unit) {
				throw new UnsupportedOperationException();
			}
		};
		return new ZppOutgoingSource(db, Runnable::run, bus, taskScheduler,
				clock, OversizedRecordTest.codec(), contact, 60_000,
				FRAME_RECORD_BYTES, scheduler, new LocalMessageLog(() -> 0L));
	}

	private Collection<Message> batch(long capacity) {
		List<Message> batch = new ArrayList<>();
		for (Message m : new ArrayList<>(toSend)) {
			if (m.getRawLength() > MAX_ONE_RECORD_MESSAGE_LENGTH) {
				batch.add(m);
				toSend.remove(m);
				continue;
			}
			if (capacity < RECORD_HEADER_BYTES + m.getRawLength()) continue;
			batch.add(m);
			toSend.remove(m);
			capacity -= RECORD_HEADER_BYTES + m.getRawLength();
		}
		return batch.isEmpty() ? null : batch;
	}

	private long nextSendTime() {
		for (Message m : toSend) {
			if (!unshared.contains(m.getId())) return 0L;
		}
		return Long.MAX_VALUE;
	}

	private void advance(long millis) {
		long end = now + millis;
		int ran = 0;
		while (ran < 10_000) {
			Task next = null;
			for (Task t : tasks) {
				if (t.cancelled || t.due > end) continue;
				if (next == null || t.due < next.due) next = t;
			}
			if (next == null) break;
			tasks.remove(next);
			now = Math.max(now, next.due);
			next.run.run();
			ran++;
		}
		now = end;
	}

	private static Object run(Object lambda, String methodName)
			throws Exception {
		Method call = null;
		for (Method m : lambda.getClass().getMethods()) {
			if (m.getName().equals(methodName)) call = m;
		}
		if (call == null) throw new AssertionError();
		call.setAccessible(true);
		return call.invoke(lambda, new Transaction(null, false));
	}

	private Message message(int bodyLength) {
		return new Message(new MessageId(getRandomId()),
				new GroupId(getRandomId()), now, new byte[bodyLength]);
	}

	private void offerAgain(ZppOutgoingSource source, MessageId id) {
		toOffer.add(new Offer(Collections.singletonList(id)));
		java.util.Map<ContactId, Boolean> visible =
				Collections.singletonMap(contact, true);
		source.eventOccurred(new MessageSharedEvent(id, new GroupId(
				getRandomId()), visible));
	}

	@Test
	public void theDeliverableBodyLimitLeavesOnlyASmallMargin()
			throws Exception {
		assertTrue(OversizedRecordTest.codec().encodeMessage(
				message(LARGEST_FITTING_BODY)).length <= MAX_SYNC_RECORD_BYTES);
		assertTrue(OversizedRecordTest.codec().encodeMessage(
				message(LARGEST_FITTING_BODY + 1)).length
				> MAX_SYNC_RECORD_BYTES);
		assertEquals(MESSAGE_HEADER_LENGTH + LARGEST_FITTING_BODY,
				MAX_ONE_RECORD_MESSAGE_LENGTH);
		assertTrue(OversizedRecordTest.codec().encodeMessage(
				message(MAX_DELIVERABLE_MESSAGE_BODY_LENGTH)).length
				<= MAX_SYNC_RECORD_BYTES);
		assertTrue(LARGEST_FITTING_BODY - MAX_DELIVERABLE_MESSAGE_BODY_LENGTH
				< 1024);
		assertEquals(ZmmConstants.MAX_RECORD_BYTES, MAX_SYNC_RECORD_BYTES);
	}

	@Test
	public void aMessageThatCanNeverFitIsNoLongerOfferedToAnyone()
			throws Exception {
		Message huge = message(LARGEST_FITTING_BODY + 1);
		toSend.add(huge);
		ZppOutgoingSource source = source();

		source.eventOccurred(new MessageRequestedEvent(contact));

		assertEquals(0, queued.size());
		assertEquals(Collections.singletonList(huge.getId()), unshared);
	}

	@Test
	public void aDroppedMessageIsNotOfferedAgainInTheSameConnection()
			throws Exception {
		Message huge = message(LARGEST_FITTING_BODY + 1);
		toSend.add(huge);
		ZppOutgoingSource source = source();
		source.eventOccurred(new MessageRequestedEvent(contact));

		offerAgain(source, huge.getId());

		assertEquals("no offer for a message that can never be sent", 0,
				queued.size());
	}

	@Test
	public void anOldMessageTooLargeForAnyBatchIsUnsharedWithoutALoop()
			throws Exception {
		Message old = message(3 * 1024 * 1024);
		toSend.add(old);
		ZppOutgoingSource source = source();

		source.start();
		advance(10 * MINUTE);

		assertEquals(Collections.singletonList(old.getId()), unshared);
		assertEquals(0, queued.size());
		assertTrue("database passes: " + batchPasses.get(),
				batchPasses.get() <= 3);
	}

	@Test
	public void aFittingMessageBehindAFullLaneLeavesOnceRoomFrees()
			throws Exception {
		Message filler = message(600_000);
		toSend.add(filler);
		ZppOutgoingSource source = source();
		source.eventOccurred(new MessageRequestedEvent(contact));
		int fillerFrames = queued.size();
		assertTrue(fillerFrames > 1);

		bulkDepth = 100;
		Message waiting = message(900_000);
		toSend.add(waiting);
		source.eventOccurred(new MessageRequestedEvent(contact));
		int before = batchPasses.get();
		advance(MINUTE);

		assertEquals("nothing leaves while the lane is full", fillerFrames,
				queued.size());
		assertTrue("database passes: " + (batchPasses.get() - before),
				batchPasses.get() - before <= 15);

		bulkDepth = 0;
		sentCallbacks.get(fillerFrames - 1).run();

		assertTrue(queued.size() > fillerFrames);
		assertTrue(toSend.isEmpty());
		assertTrue(unshared.isEmpty());
	}

	@Test
	public void theLargestMessageThatFitsIsQueuedAndStaysOffered()
			throws Exception {
		Message fits = message(LARGEST_FITTING_BODY);
		toSend.add(fits);
		ZppOutgoingSource source = source();

		source.eventOccurred(new MessageRequestedEvent(contact));

		assertTrue(queued.size() > 1);
		assertTrue(unshared.isEmpty());
	}

	@Test
	public void anOfferNeverAsksForMoreIdsThanOneRecordHolds()
			throws Exception {
		ZppOutgoingSource source = source();

		source.eventOccurred(new MessageSharedEvent(new MessageId(
				getRandomId()), new GroupId(getRandomId()),
				Collections.singletonMap(contact, true)));

		assertTrue(offerIdsAsked.get() > 0);
		List<MessageId> ids = new ArrayList<>();
		for (int i = 0; i < offerIdsAsked.get(); i++) {
			ids.add(new MessageId(getRandomId()));
		}
		assertTrue(OversizedRecordTest.codec().encodeOffer(new Offer(ids))
				.length <= ZmmConstants.MAX_RECORD_BYTES);
	}
}

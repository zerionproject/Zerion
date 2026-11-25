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
import org.zerionproject.core.api.sync.event.MessageSharedEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.message.ZmmConstants;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_DELIVERABLE_MESSAGE_BODY_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_SYNC_RECORD_BYTES;
import static org.zerionproject.core.api.sync.SyncConstants.MESSAGE_HEADER_LENGTH;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class NeverFittingRecordTest {

	private static final long NOW = 1_700_000_000_000L;
	private static final int FRAME_RECORD_BYTES = 1_600;
	private static final int LARGEST_FITTING_BODY = 994_146;

	private final ContactId contact = new ContactId(1);
	private final List<byte[]> queued =
			Collections.synchronizedList(new ArrayList<>());
	private final List<MessageId> unshared = new ArrayList<>();
	private final AtomicInteger offerIdsAsked = new AtomicInteger(-1);
	private final List<Message> toSend = new ArrayList<>();
	private final List<Offer> toOffer = new ArrayList<>();

	private ZppOutgoingSource source() throws Exception {
		ZppSendScheduler scheduler = new ZppSendScheduler(r -> {
		}, () -> true) {
			@Override
			public void enqueueRecord(byte[] record, boolean userOriginated,
					boolean opensWindow, int rank, boolean bulkLane,
					Runnable onSent) {
				queued.add(record);
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
						if (toSend.isEmpty()) return null;
						List<Message> batch = new ArrayList<>(toSend);
						toSend.clear();
						return batch;
					}
					if (name.equals("generateOffer")) {
						offerIdsAsked.set((Integer) args[2]);
						return toOffer.isEmpty() ? null : toOffer.remove(0);
					}
					if (name.equals("setMessageNotShared")) {
						unshared.add((MessageId) args[1]);
						return null;
					}
					if (name.equals("getNextSendTime")) return Long.MAX_VALUE;
					return null;
				});
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
				OversizedRecordTest.codec(), contact, 60_000,
				FRAME_RECORD_BYTES, scheduler, new LocalMessageLog(() -> 0L));
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

	private static Message message(int bodyLength) {
		return new Message(new MessageId(getRandomId()),
				new GroupId(getRandomId()), NOW, new byte[bodyLength]);
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
		assertTrue(OversizedRecordTest.codec().encodeMessage(
				message(MAX_DELIVERABLE_MESSAGE_BODY_LENGTH)).length
				<= MAX_SYNC_RECORD_BYTES);
		assertTrue(LARGEST_FITTING_BODY - MAX_DELIVERABLE_MESSAGE_BODY_LENGTH
				< 1024);
		assertEquals(ZmmConstants.MAX_RECORD_BYTES, MAX_SYNC_RECORD_BYTES);
		assertTrue(MESSAGE_HEADER_LENGTH + MAX_DELIVERABLE_MESSAGE_BODY_LENGTH
				< MAX_SYNC_RECORD_BYTES);
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

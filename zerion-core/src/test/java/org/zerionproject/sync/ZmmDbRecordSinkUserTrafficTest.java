package org.zerionproject.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.MessageStatus;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmFragmenter;
import org.zerionproject.message.ZmmRecord;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class ZmmDbRecordSinkUserTrafficTest {

	private final ContactId contact = new ContactId(1);
	private final LocalMessageLog log = new LocalMessageLog(() -> 1_000L);
	private final Set<MessageId> seen = new HashSet<>();
	private final Set<MessageId> missing = new HashSet<>();
	private final List<String> calls = new ArrayList<>();
	private long nextRecord = 0;

	@Test
	public void aRequestForAMarkedMessageIsNotedBeforeItReachesTheDatabase()
			throws Exception {
		ZmmSyncCodec codec = OversizedRecordTest.codec();
		ZmmDbRecordSink sink = new ZmmDbRecordSink(db(), codec, log);
		MessageId chosen = new MessageId(getRandomId());
		MessageId automatic = new MessageId(getRandomId());
		log.noteUserPart(chosen);

		deliver(sink, codec.encodeRequest(
				new Request(Arrays.asList(automatic, chosen))));

		assertEquals(Collections.singletonList("request, waited for: true"),
				calls);
		assertTrue(log.hasWanted(contact));
		assertTrue(log.takeUserTraffic(contact, chosen));
		assertFalse(log.hasWanted(contact));
	}

	@Test
	public void aRequestForUnmarkedSeenOrMissingMessagesIsNotWaitedFor()
			throws Exception {
		ZmmSyncCodec codec = OversizedRecordTest.codec();
		ZmmDbRecordSink sink = new ZmmDbRecordSink(db(), codec, log);
		MessageId automatic = new MessageId(getRandomId());
		MessageId delivered = new MessageId(getRandomId());
		MessageId removed = new MessageId(getRandomId());
		log.noteUserPart(delivered);
		log.noteUserPart(removed);
		seen.add(delivered);
		missing.add(removed);

		deliver(sink, codec.encodeRequest(new Request(
				Arrays.asList(automatic, delivered, removed))));

		assertEquals(Collections.singletonList("request, waited for: false"),
				calls);
		assertFalse(log.hasWanted(contact));
	}

	@Test
	public void anAcknowledgementEndsTheWaitAndTheMarkOfThePart()
			throws Exception {
		ZmmSyncCodec codec = OversizedRecordTest.codec();
		ZmmDbRecordSink sink = new ZmmDbRecordSink(db(), codec, log);
		MessageId chosen = new MessageId(getRandomId());
		log.noteUserPart(chosen);
		deliver(sink, codec.encodeRequest(
				new Request(Collections.singletonList(chosen))));
		assertTrue(log.hasWanted(contact));

		deliver(sink, codec.encodeAck(
				new Ack(Collections.singletonList(chosen))));

		assertEquals(Arrays.asList("request, waited for: true", "ack"),
				calls);
		assertFalse(log.hasWanted(contact));
		assertFalse(log.isUserTraffic(chosen));
	}

	private void deliver(ZmmDbRecordSink sink, byte[] syncRecord) {
		for (byte[] frame : ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				syncRecord, nextRecord++, 1_600)) {
			sink.deliver(contact.getInt(), 1L, ZmmRecord.getType(frame),
					ZmmRecord.getPayload(frame));
		}
	}

	private DatabaseComponent db() {
		return (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if (name.equals("transaction")
							|| name.equals("transactionWithResult")) {
						Method task = null;
						for (Method m : args[1].getClass().getMethods()) {
							if (m.getName().equals("run")
									|| m.getName().equals("call")) {
								task = m;
							}
						}
						if (task == null) throw new AssertionError();
						task.setAccessible(true);
						try {
							return task.invoke(args[1],
									new Transaction(null, (Boolean) args[0]));
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					}
					if (name.equals("getMessageStatus")) {
						MessageId id = (MessageId) args[2];
						if (missing.contains(id)) {
							throw new NoSuchMessageException();
						}
						return new MessageStatus(id, contact, false,
								seen.contains(id));
					}
					if (name.equals("receiveRequest")) {
						calls.add("request, waited for: "
								+ log.hasWanted(contact));
					}
					if (name.equals("receiveAck")) calls.add("ack");
					return null;
				});
	}
}

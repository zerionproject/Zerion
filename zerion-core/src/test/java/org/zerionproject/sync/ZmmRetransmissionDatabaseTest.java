package org.zerionproject.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.record.RecordWriterFactory;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageContext;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.SyncRecordReaderFactory;
import org.zerionproject.core.api.sync.SyncRecordWriterFactory;
import org.zerionproject.core.api.sync.event.MessageAddedEvent;
import org.zerionproject.core.api.sync.event.MessageToAckEvent;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.zerionproject.core.api.sync.validation.ValidationManager;
import org.zerionproject.core.db.HyperSqlDatabaseForTests;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmFragmenter;
import org.zerionproject.message.ZmmRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.api.sync.validation.MessageState.DELIVERED;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ZmmRetransmissionDatabaseTest extends BrambleTestCase {

	private static final int MAX_RECORD_BYTES = 700;

	private final File testDir = getTestDirectory();
	private final SecretKey dbKey = getSecretKey();
	private final Group group = getGroup(getClientId(), 1);
	private MessageFactory messageFactory;
	private ZmmSyncCodec codec;
	private Bus bus;
	private DatabaseComponent db;
	private ZmmDbRecordSink sink;
	private int delivered;
	private ContactId contact;

	@Before
	public void setUp() throws Exception {
		CryptoComponent crypto = (CryptoComponent) construct(
				"org.zerionproject.core.crypto.CryptoComponentImpl",
				new TestSecureRandomProvider(), null);
		messageFactory = (MessageFactory) construct(
				"org.zerionproject.core.sync.MessageFactoryImpl", crypto);
		RecordReaderFactory readers = (RecordReaderFactory) construct(
				"org.zerionproject.core.record.RecordReaderFactoryImpl");
		RecordWriterFactory writers = (RecordWriterFactory) construct(
				"org.zerionproject.core.record.RecordWriterFactoryImpl");
		SyncRecordReaderFactory syncReaders = (SyncRecordReaderFactory)
				construct("org.zerionproject.core.sync.SyncRecordReaderFactoryImpl",
						messageFactory, readers);
		SyncRecordWriterFactory syncWriters = (SyncRecordWriterFactory)
				construct("org.zerionproject.core.sync.SyncRecordWriterFactoryImpl",
						messageFactory, writers);
		codec = new ZmmSyncCodec(syncWriters, syncReaders);
		open();
		Identity identity = getIdentity();
		contact = db.transactionWithResult(false, txn -> {
			db.addIdentity(txn, identity);
			ContactId c = db.addContact(txn, getAuthor(),
					identity.getLocalAuthor().getId(), null, true);
			db.addGroup(txn, group);
			db.setGroupVisibility(txn, c, group.getId(), SHARED);
			return c;
		});
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	private void open() throws Exception {
		bus = new Bus();
		db = HyperSqlDatabaseForTests.open(testDir, dbKey, bus,
				messageFactory);
		ValidationManager validation = (ValidationManager) construct(
				"org.zerionproject.core.sync.validation.ValidationManagerImpl",
				db, (java.util.concurrent.Executor) Runnable::run,
				(java.util.concurrent.Executor) Runnable::run);
		validation.registerMessageValidator(group.getClientId(),
				group.getMajorVersion(),
				(m, g) -> new MessageContext(new Metadata()));
		validation.registerIncomingMessageHook(group.getClientId(),
				group.getMajorVersion(), (txn, m, meta) -> {
					delivered++;
					return IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
				});
		bus.addListener((EventListener) validation);
		bus.drain();
		sink = new ZmmDbRecordSink(db, codec);
	}

	@Test
	public void aRetransmittedFragmentedRecordIsHeldAndDeliveredOnce()
			throws Exception {
		Message m = messageFactory.createMessage(group.getId(),
				System.currentTimeMillis(), getRandomBytes(3000));
		List<byte[]> fragments = ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				codec.encodeMessage(m), 11, MAX_RECORD_BYTES);
		assertTrue(fragments.size() > 3);

		deliver(1, fragments);
		sink.onDisconnected(contact.getInt(), 1);
		assertHeldOnce(m);
		assertEquals("stored once", 1, bus.count(MessageAddedEvent.class));
		assertEquals("delivered to the application once", 1, delivered);

		deliver(2, fragments);
		assertHeldOnce(m);
		assertEquals(1, bus.count(MessageAddedEvent.class));
		assertEquals(1, delivered);
		assertEquals("the copy is acknowledged again", 2,
				bus.count(MessageToAckEvent.class));

		List<byte[]> scrambled = new ArrayList<>();
		for (byte[] f : fragments) {
			scrambled.add(f);
			scrambled.add(f);
		}
		Collections.reverse(scrambled);
		deliver(3, scrambled);
		assertHeldOnce(m);
		assertEquals(1, bus.count(MessageAddedEvent.class));
		assertEquals(1, delivered);

		db.close();
		db = null;
		delivered = 0;
		open();
		deliver(4, fragments);
		assertHeldOnce(m);
		assertEquals("nothing is added again after a restart", 0,
				bus.count(MessageAddedEvent.class));
		assertEquals("nor delivered again", 0, delivered);
		assertEquals(1, bus.count(MessageToAckEvent.class));
	}

	private void deliver(long session, List<byte[]> records) {
		for (byte[] r : records) {
			sink.deliver(contact.getInt(), session, ZmmRecord.getType(r),
					ZmmRecord.getPayload(r));
			bus.drain();
		}
	}

	private void assertHeldOnce(Message m) throws Exception {
		Collection<MessageId> ids = db.transactionWithResult(true, txn ->
				db.getMessageIds(txn, group.getId()));
		assertEquals(Collections.singletonList(m.getId()),
				new ArrayList<>(ids));
		assertEquals(DELIVERED, db.transactionWithResult(true, txn ->
				db.getMessageState(txn, m.getId())));
	}

	private static Object construct(String className, Object... args)
			throws Exception {
		Class<?> c = Class.forName(className);
		for (Constructor<?> ctor : c.getDeclaredConstructors()) {
			if (ctor.getParameterCount() != args.length) continue;
			ctor.setAccessible(true);
			try {
				return ctor.newInstance(args);
			} catch (IllegalArgumentException ignored) {
			}
		}
		throw new AssertionError("no constructor for " + className);
	}

	private static final class Bus implements EventBus {

		private final List<EventListener> listeners =
				new CopyOnWriteArrayList<>();
		private final List<Event> seen = new CopyOnWriteArrayList<>();
		private final java.util.ArrayDeque<Event> pending =
				new java.util.ArrayDeque<>();

		@Override
		public void addListener(EventListener l) {
			listeners.add(l);
		}

		@Override
		public void removeListener(EventListener l) {
			listeners.remove(l);
		}

		@Override
		public void broadcast(Event e) {
			seen.add(e);
			pending.add(e);
		}

		void drain() {
			Event e;
			while ((e = pending.poll()) != null) {
				for (EventListener l : listeners) l.eventOccurred(e);
			}
		}

		int count(Class<? extends Event> type) {
			int n = 0;
			for (Event e : seen) if (type.isInstance(e)) n++;
			return n;
		}
	}
}

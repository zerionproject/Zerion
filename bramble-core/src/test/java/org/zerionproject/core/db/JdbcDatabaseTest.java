package org.zerionproject.core.db;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.HybridCommitmentPublicKey;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.MessageDeletedException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.MessageStatus;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.transport.IncomingKeys;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.transport.OutgoingKeys;
import org.zerionproject.core.api.transport.TransportKeySet;
import org.zerionproject.core.api.transport.TransportKeys;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.SettableClock;
import org.zerionproject.core.test.TestDatabaseConfig;
import org.zerionproject.core.test.TestMessageFactory;
import org.zerionproject.core.test.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.zerionproject.core.api.db.DatabaseComponent.NO_CLEANUP_DEADLINE;
import static org.zerionproject.core.api.db.DatabaseComponent.TIMER_NOT_STARTED;
import static org.zerionproject.core.api.db.Metadata.REMOVE;
import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.zerionproject.core.api.record.Record.RECORD_HEADER_BYTES;
import static org.zerionproject.core.api.sync.Group.Visibility.INVISIBLE;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.api.sync.Group.Visibility.VISIBLE;
import static org.zerionproject.core.api.sync.validation.MessageState.DELIVERED;
import static org.zerionproject.core.api.sync.validation.MessageState.INVALID;
import static org.zerionproject.core.api.sync.validation.MessageState.PENDING;
import static org.zerionproject.core.api.sync.validation.MessageState.UNKNOWN;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getAgreementPrivateKey;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getPendingContact;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.FORMAT_VERSION_HYBRID;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_BYTES;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_RENDEZVOUS_X25519_BYTES;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.zerionproject.core.util.StringUtils.getRandomString;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public abstract class JdbcDatabaseTest extends BrambleTestCase {

	private static final int ONE_MEGABYTE = 1024 * 1024;

	private static final int MAX_LATENCY = 30 * 1000;

	private final SecretKey key = getSecretKey();
	private final File testDir = getTestDirectory();
	private final GroupId groupId;
	private final ClientId clientId;
	private final int majorVersion;
	private final Group group;
	private final Author author;
	private final Identity identity;
	private final LocalAuthor localAuthor;
	private final Message message;
	private final MessageId messageId;
	private final TransportId transportId;
	private final ContactId contactId;
	private final KeySetId keySetId, keySetId1;
	private final PendingContact pendingContact;
	private final Random random = new Random();

	JdbcDatabaseTest() {
		clientId = getClientId();
		majorVersion = 123;
		group = getGroup(clientId, majorVersion);
		groupId = group.getId();
		author = getAuthor();
		identity = getIdentity();
		localAuthor = identity.getLocalAuthor();
		message = getMessage(groupId);
		messageId = message.getId();
		transportId = getTransportId();
		contactId = new ContactId(1);
		keySetId = new KeySetId(1);
		keySetId1 = new KeySetId(2);
		pendingContact = getPendingContact();
	}

	protected abstract JdbcDatabase createDatabase(DatabaseConfig config,
			MessageFactory messageFactory, Clock clock);

	@Before
	public void setUp() {
		assertTrue(testDir.mkdirs());
	}

	@Test
	public void testPersistence() throws Exception {

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();
		assertFalse(db.containsContact(txn, contactId));
		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		assertTrue(db.containsContact(txn, contactId));
		assertFalse(db.containsGroup(txn, groupId));
		db.addGroup(txn, group);
		assertTrue(db.containsGroup(txn, groupId));
		assertFalse(db.containsMessage(txn, messageId));
		db.addMessage(txn, message, DELIVERED, true, false, null);
		assertTrue(db.containsMessage(txn, messageId));
		db.commitTransaction(txn);
		db.close();

		db = open(true);
		txn = db.startTransaction();
		assertTrue(db.containsContact(txn, contactId));
		assertTrue(db.containsGroup(txn, groupId));
		assertTrue(db.containsMessage(txn, messageId));
		assertEquals(groupId, db.getGroupId(txn, messageId));
		assertArrayEquals(message.getBody(),
				db.getMessage(txn, messageId).getBody());

		db.removeMessage(txn, messageId);
		db.removeContact(txn, contactId);
		db.removeGroup(txn, groupId);
		db.commitTransaction(txn);
		db.close();

		db = open(true);
		txn = db.startTransaction();
		assertFalse(db.containsContact(txn, contactId));
		assertFalse(db.containsGroup(txn, groupId));
		assertFalse(db.containsMessage(txn, messageId));
		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testReopenSameInstanceAfterClose() throws Exception {
		// Open and populate a single database instance.
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.commitTransaction(txn);
		db.close();

		// Reopen the SAME instance in the same process, as happens after Exit
		// followed by an immediate reopen (the pending process-kill is
		// cancelled, so the instance is reused). This must not fail with
		// DbClosedException and must still see the persisted data.
		db.open(key, null);
		txn = db.startTransaction();
		assertTrue(db.containsContact(txn, contactId));
		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testRemovingGroupRemovesMessage() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		assertTrue(db.containsMessage(txn, messageId));
		db.removeGroup(txn, groupId);
		assertFalse(db.containsMessage(txn, messageId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testSendableMessagesMustHaveSeenFlagFalse() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		assertOneMessageToSendEagerly(db, txn);
		assertOneMessageToSendLazily(db, txn);

		db.raiseSeenFlag(txn, contactId, messageId);
		assertNothingToSendEagerly(db, txn);
		assertNothingToSendLazily(db, txn);

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testSendableMessagesMustBeDelivered() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);
		db.addMessage(txn, message, UNKNOWN, true, false, null);

		assertNothingToSendLazily(db, txn);
		assertNothingToSendEagerly(db, txn);

		db.setMessageState(txn, messageId, DELIVERED);
		assertOneMessageToSendLazily(db, txn);
		assertOneMessageToSendEagerly(db, txn);

		db.setMessageState(txn, messageId, INVALID);
		assertNothingToSendLazily(db, txn);
		assertNothingToSendEagerly(db, txn);

		db.setMessageState(txn, messageId, PENDING);
		assertNothingToSendLazily(db, txn);
		assertNothingToSendEagerly(db, txn);

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testSendableMessagesMustHaveSharedGroup() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		assertNothingToSendLazily(db, txn);
		assertNothingToSendEagerly(db, txn);

		db.addGroupVisibility(txn, contactId, groupId, false);
		assertNothingToSendLazily(db, txn);
		assertNothingToSendEagerly(db, txn);

		db.setGroupVisibility(txn, contactId, groupId, true);
		assertOneMessageToSendEagerly(db, txn);
		assertOneMessageToSendLazily(db, txn);

		db.setGroupVisibility(txn, contactId, groupId, false);
		assertNothingToSendLazily(db, txn);
		assertNothingToSendEagerly(db, txn);

		db.removeGroupVisibility(txn, contactId, groupId);
		assertNothingToSendLazily(db, txn);
		assertNothingToSendEagerly(db, txn);

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testSendableMessagesMustBeShared() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);
		db.addMessage(txn, message, DELIVERED, false, false, null);

		assertNothingToSendLazily(db, txn);
		assertNothingToSendEagerly(db, txn);

		db.setMessageShared(txn, messageId, true);
		assertOneMessageToSendLazily(db, txn);
		assertOneMessageToSendEagerly(db, txn);

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testSendableMessagesMustFitCapacity() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		assertOneMessageToSendLazily(db, txn);
		assertOneMessageToSendEagerly(db, txn);
		long capacity = RECORD_HEADER_BYTES + message.getRawLength() - 1;
		Collection<MessageId> ids =
				db.getMessagesToSend(txn, contactId, capacity, MAX_LATENCY);
		assertTrue(ids.isEmpty());

		capacity = RECORD_HEADER_BYTES + message.getRawLength();
		ids = db.getMessagesToSend(txn, contactId, capacity, MAX_LATENCY);
		assertEquals(singletonList(messageId), ids);

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testMessagesToAck() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, false);

		assertFalse(
				db.containsMessagesToSend(txn, contactId, MAX_LATENCY, false));
		assertFalse(
				db.containsMessagesToSend(txn, contactId, MAX_LATENCY, true));

		Message message1 = getMessage(groupId);
		MessageId messageId1 = message1.getId();
		db.addMessage(txn, message, DELIVERED, true, false, contactId);
		db.addMessage(txn, message1, DELIVERED, true, false, contactId);

		assertTrue(db.containsAcksToSend(txn, contactId));
		Collection<MessageId> ids = db.getMessagesToAck(txn, contactId, 1234);
		assertEquals(asList(messageId, messageId1), ids);

		db.lowerAckFlag(txn, contactId, asList(messageId, messageId1));

		assertFalse(db.containsAcksToSend(txn, contactId));
		assertEquals(emptyList(), db.getMessagesToAck(txn, contactId, 1234));

		db.raiseAckFlag(txn, contactId, messageId);
		db.raiseAckFlag(txn, contactId, messageId1);

		assertTrue(db.containsAcksToSend(txn, contactId));
		ids = db.getMessagesToAck(txn, contactId, 1234);
		assertEquals(asList(messageId, messageId1), ids);

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testOutstandingMessageAcked() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		assertOneMessageToSendLazily(db, txn);
		assertOneMessageToSendEagerly(db, txn);

		db.updateRetransmissionData(txn, contactId, messageId, MAX_LATENCY);

		assertNothingToSendLazily(db, txn);
		assertOneMessageToSendEagerly(db, txn);

		db.raiseSeenFlag(txn, contactId, messageId);

		assertNothingToSendLazily(db, txn);
		assertNothingToSendEagerly(db, txn);

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testCloseWaitsForCommit() throws Exception {
		CountDownLatch closing = new CountDownLatch(1);
		CountDownLatch closed = new CountDownLatch(1);
		AtomicBoolean transactionFinished = new AtomicBoolean(false);
		AtomicBoolean error = new AtomicBoolean(false);
		Database<Connection> db = open(false);

		Connection txn = db.startTransaction();

		Thread close = new Thread(() -> {
			try {
				closing.countDown();
				db.close();
				if (!transactionFinished.get()) error.set(true);
				closed.countDown();
			} catch (Exception e) {
				error.set(true);
			}
		});
		close.start();
		closing.await();

		Thread.sleep(10);
		transactionFinished.set(true);

		db.commitTransaction(txn);

		assertTrue(closed.await(5, SECONDS));

		assertFalse(error.get());
	}

	@Test
	public void testCloseWaitsForAbort() throws Exception {
		CountDownLatch closing = new CountDownLatch(1);
		CountDownLatch closed = new CountDownLatch(1);
		AtomicBoolean transactionFinished = new AtomicBoolean(false);
		AtomicBoolean error = new AtomicBoolean(false);
		Database<Connection> db = open(false);

		Connection txn = db.startTransaction();

		Thread close = new Thread(() -> {
			try {
				closing.countDown();
				db.close();
				if (!transactionFinished.get()) error.set(true);
				closed.countDown();
			} catch (Exception e) {
				error.set(true);
			}
		});
		close.start();
		closing.await();

		Thread.sleep(10);
		transactionFinished.set(true);

		db.abortTransaction(txn);

		assertTrue(closed.await(5, SECONDS));

		assertFalse(error.get());
	}

	@Test
	public void testUpdateSettings() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		Settings s = new Settings();
		s.put("foo", "foo");
		s.put("bar", "bar");
		db.mergeSettings(txn, s, "test");
		assertEquals(s, db.getSettings(txn, "test"));

		Settings s1 = new Settings();
		s1.put("bar", "baz");
		s1.put("bam", "bam");
		db.mergeSettings(txn, s1, "test");

		Settings merged = new Settings();
		merged.put("foo", "foo");
		merged.put("bar", "baz");
		merged.put("bam", "bam");
		assertEquals(merged, db.getSettings(txn, "test"));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testContainsVisibleMessageRequiresMessageInDatabase()
			throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);

		assertFalse(db.containsVisibleMessage(txn, contactId, messageId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testContainsVisibleMessageRequiresGroupInDatabase()
			throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));

		assertFalse(db.containsVisibleMessage(txn, contactId, messageId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testContainsVisibleMessageRequiresVisibileGroup()
			throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		assertFalse(db.containsVisibleMessage(txn, contactId, messageId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testGroupVisibility() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);

		assertEquals(INVISIBLE, db.getGroupVisibility(txn, contactId, groupId));
		assertTrue(db.getGroupVisibility(txn, groupId).isEmpty());

		db.addGroupVisibility(txn, contactId, groupId, false);
		assertEquals(VISIBLE, db.getGroupVisibility(txn, contactId, groupId));
		assertEquals(singletonMap(contactId, false),
				db.getGroupVisibility(txn, groupId));

		db.setGroupVisibility(txn, contactId, groupId, true);
		assertEquals(SHARED, db.getGroupVisibility(txn, contactId, groupId));
		assertEquals(singletonMap(contactId, true),
				db.getGroupVisibility(txn, groupId));

		db.setGroupVisibility(txn, contactId, groupId, false);
		assertEquals(VISIBLE, db.getGroupVisibility(txn, contactId, groupId));
		assertEquals(singletonMap(contactId, false),
				db.getGroupVisibility(txn, groupId));

		db.removeGroupVisibility(txn, contactId, groupId);
		assertEquals(INVISIBLE, db.getGroupVisibility(txn, contactId, groupId));
		assertTrue(db.getGroupVisibility(txn, groupId).isEmpty());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testTransportKeys() throws Exception {
		long timePeriod = 123, timePeriod1 = 234;
		boolean active = random.nextBoolean();
		TransportKeys keys = createTransportKeys(timePeriod, active);
		TransportKeys keys1 = createTransportKeys(timePeriod1, active);

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		assertFalse(db.containsTransportKeys(txn, contactId, transportId));
		assertEquals(emptyList(), db.getTransportKeys(txn, transportId));
		assertTrue(db.getTransportsWithKeys(txn).isEmpty());

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addTransport(txn, transportId, 123);
		assertEquals(keySetId, db.addTransportKeys(txn, contactId, keys));
		assertEquals(keySetId1, db.addTransportKeys(txn, contactId, keys1));

		assertTrue(db.containsTransportKeys(txn, contactId, transportId));
		Collection<TransportKeySet> allKeys =
				db.getTransportKeys(txn, transportId);
		assertEquals(2, allKeys.size());
		for (TransportKeySet ks : allKeys) {
			assertEquals(contactId, ks.getContactId());
			if (ks.getKeySetId().equals(keySetId)) {
				assertKeysEquals(keys, ks.getKeys());
			} else {
				assertEquals(keySetId1, ks.getKeySetId());
				assertKeysEquals(keys1, ks.getKeys());
			}
		}
		assertEquals(singletonMap(contactId, singletonList(transportId)),
				db.getTransportsWithKeys(txn));

		TransportKeys updated = createTransportKeys(timePeriod + 1, active);
		TransportKeys updated1 =
				createTransportKeys(timePeriod1 + 1, active);
		db.updateTransportKeys(txn, new TransportKeySet(keySetId, contactId,
				null, updated));
		db.updateTransportKeys(txn, new TransportKeySet(keySetId1, contactId,
				null, updated1));

		assertTrue(db.containsTransportKeys(txn, contactId, transportId));
		allKeys = db.getTransportKeys(txn, transportId);
		assertEquals(2, allKeys.size());
		for (TransportKeySet ks : allKeys) {
			assertEquals(contactId, ks.getContactId());
			if (ks.getKeySetId().equals(keySetId)) {
				assertKeysEquals(updated, ks.getKeys());
			} else {
				assertEquals(keySetId1, ks.getKeySetId());
				assertKeysEquals(updated1, ks.getKeys());
			}
		}
		assertEquals(singletonMap(contactId, singletonList(transportId)),
				db.getTransportsWithKeys(txn));

		db.removeContact(txn, contactId);
		assertFalse(db.containsTransportKeys(txn, contactId, transportId));
		assertEquals(emptyList(), db.getTransportKeys(txn, transportId));
		assertTrue(db.getTransportsWithKeys(txn).isEmpty());

		db.commitTransaction(txn);
		db.close();
	}

	private void assertKeysEquals(TransportKeys expected,
			TransportKeys actual) {
		assertEquals(expected.getTransportId(), actual.getTransportId());
		assertEquals(expected.getTimePeriod(), actual.getTimePeriod());
		assertKeysEquals(expected.getPreviousIncomingKeys(),
				actual.getPreviousIncomingKeys());
		assertKeysEquals(expected.getCurrentIncomingKeys(),
				actual.getCurrentIncomingKeys());
		assertKeysEquals(expected.getNextIncomingKeys(),
				actual.getNextIncomingKeys());
		assertKeysEquals(expected.getCurrentOutgoingKeys(),
				actual.getCurrentOutgoingKeys());
		if (expected.isHandshakeMode()) {
			assertTrue(actual.isHandshakeMode());
			assertArrayEquals(expected.getRootKey().getBytes(),
					actual.getRootKey().getBytes());
			assertEquals(expected.isAlice(), actual.isAlice());
		} else {
			assertFalse(actual.isHandshakeMode());
		}
	}

	private void assertKeysEquals(IncomingKeys expected, IncomingKeys actual) {
		assertArrayEquals(expected.getTagKey().getBytes(),
				actual.getTagKey().getBytes());
		assertArrayEquals(expected.getHeaderKey().getBytes(),
				actual.getHeaderKey().getBytes());
		assertEquals(expected.getTimePeriod(), actual.getTimePeriod());
		assertEquals(expected.getWindowBase(), actual.getWindowBase());
		assertArrayEquals(expected.getWindowBitmap(), actual.getWindowBitmap());
	}

	private void assertKeysEquals(OutgoingKeys expected, OutgoingKeys actual) {
		assertArrayEquals(expected.getTagKey().getBytes(),
				actual.getTagKey().getBytes());
		assertArrayEquals(expected.getHeaderKey().getBytes(),
				actual.getHeaderKey().getBytes());
		assertEquals(expected.getTimePeriod(), actual.getTimePeriod());
		assertEquals(expected.getStreamCounter(), actual.getStreamCounter());
		assertEquals(expected.isActive(), actual.isActive());
	}

	@Test
	public void testHandshakeKeys() throws Exception {
		long timePeriod = 123, timePeriod1 = 234;
		boolean alice = random.nextBoolean();
		SecretKey rootKey = getSecretKey();
		SecretKey rootKey1 = getSecretKey();
		TransportKeys keys = createHandshakeKeys(timePeriod, rootKey, alice);
		TransportKeys keys1 = createHandshakeKeys(timePeriod1, rootKey1, alice);

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		assertEquals(emptyList(), db.getTransportKeys(txn, transportId));

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addTransport(txn, transportId, 123);
		assertEquals(keySetId, db.addTransportKeys(txn, contactId, keys));
		assertEquals(keySetId1, db.addTransportKeys(txn, contactId, keys1));

		Collection<TransportKeySet> allKeys =
				db.getTransportKeys(txn, transportId);
		assertEquals(2, allKeys.size());
		for (TransportKeySet ks : allKeys) {
			assertEquals(contactId, ks.getContactId());
			assertNull(ks.getPendingContactId());
			if (ks.getKeySetId().equals(keySetId)) {
				assertKeysEquals(keys, ks.getKeys());
			} else {
				assertEquals(keySetId1, ks.getKeySetId());
				assertKeysEquals(keys1, ks.getKeys());
			}
		}

		TransportKeys updated =
				createHandshakeKeys(timePeriod + 1, rootKey, alice);
		TransportKeys updated1 =
				createHandshakeKeys(timePeriod1 + 1, rootKey1, alice);
		db.updateTransportKeys(txn, new TransportKeySet(keySetId, contactId,
				null, updated));
		db.updateTransportKeys(txn, new TransportKeySet(keySetId1, contactId,
				null, updated1));

		allKeys = db.getTransportKeys(txn, transportId);
		assertEquals(2, allKeys.size());
		for (TransportKeySet ks : allKeys) {
			assertEquals(contactId, ks.getContactId());
			assertNull(ks.getPendingContactId());
			if (ks.getKeySetId().equals(keySetId)) {
				assertKeysEquals(updated, ks.getKeys());
			} else {
				assertEquals(keySetId1, ks.getKeySetId());
				assertKeysEquals(updated1, ks.getKeys());
			}
		}

		db.removeContact(txn, contactId);
		assertEquals(emptyList(), db.getTransportKeys(txn, transportId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testHandshakeKeysForPendingContact() throws Exception {
		long timePeriod = 123, timePeriod1 = 234;
		boolean alice = random.nextBoolean();
		SecretKey rootKey = getSecretKey();
		SecretKey rootKey1 = getSecretKey();
		TransportKeys keys = createHandshakeKeys(timePeriod, rootKey, alice);
		TransportKeys keys1 = createHandshakeKeys(timePeriod1, rootKey1, alice);

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		assertEquals(emptyList(), db.getTransportKeys(txn, transportId));

		db.addPendingContact(txn, pendingContact);
		db.addTransport(txn, transportId, 123);
		assertEquals(keySetId,
				db.addTransportKeys(txn, pendingContact.getId(), keys));
		assertEquals(keySetId1,
				db.addTransportKeys(txn, pendingContact.getId(), keys1));

		Collection<TransportKeySet> allKeys =
				db.getTransportKeys(txn, transportId);
		assertEquals(2, allKeys.size());
		for (TransportKeySet ks : allKeys) {
			assertNull(ks.getContactId());
			assertEquals(pendingContact.getId(), ks.getPendingContactId());
			if (ks.getKeySetId().equals(keySetId)) {
				assertKeysEquals(keys, ks.getKeys());
			} else {
				assertEquals(keySetId1, ks.getKeySetId());
				assertKeysEquals(keys1, ks.getKeys());
			}
		}

		TransportKeys updated =
				createHandshakeKeys(timePeriod + 1, rootKey, alice);
		TransportKeys updated1 =
				createHandshakeKeys(timePeriod1 + 1, rootKey1, alice);
		db.updateTransportKeys(txn, new TransportKeySet(keySetId, null,
				pendingContact.getId(), updated));
		db.updateTransportKeys(txn, new TransportKeySet(keySetId1, null,
				pendingContact.getId(), updated1));

		allKeys = db.getTransportKeys(txn, transportId);
		assertEquals(2, allKeys.size());
		for (TransportKeySet ks : allKeys) {
			assertNull(ks.getContactId());
			assertEquals(pendingContact.getId(), ks.getPendingContactId());
			if (ks.getKeySetId().equals(keySetId)) {
				assertKeysEquals(updated, ks.getKeys());
			} else {
				assertEquals(keySetId1, ks.getKeySetId());
				assertKeysEquals(updated1, ks.getKeys());
			}
		}

		db.removePendingContact(txn, pendingContact.getId());
		assertEquals(emptyList(), db.getTransportKeys(txn, transportId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testIncrementStreamCounter() throws Exception {
		long timePeriod = 123;
		TransportKeys keys = createTransportKeys(timePeriod, true);
		long streamCounter = keys.getCurrentOutgoingKeys().getStreamCounter();

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addTransport(txn, transportId, 123);
		assertEquals(keySetId, db.addTransportKeys(txn, contactId, keys));

		db.incrementStreamCounter(txn, transportId, keySetId);
		db.incrementStreamCounter(txn, transportId, keySetId);
		Collection<TransportKeySet> newKeys =
				db.getTransportKeys(txn, transportId);
		assertEquals(1, newKeys.size());
		TransportKeySet ks = newKeys.iterator().next();
		assertEquals(keySetId, ks.getKeySetId());
		assertEquals(contactId, ks.getContactId());
		TransportKeys k = ks.getKeys();
		assertEquals(transportId, k.getTransportId());
		OutgoingKeys outCurr = k.getCurrentOutgoingKeys();
		assertEquals(timePeriod, outCurr.getTimePeriod());
		assertEquals(streamCounter + 2, outCurr.getStreamCounter());

		assertKeysEquals(keys.getPreviousIncomingKeys(),
				k.getPreviousIncomingKeys());
		assertKeysEquals(keys.getCurrentIncomingKeys(),
				k.getCurrentIncomingKeys());
		assertKeysEquals(keys.getNextIncomingKeys(), k.getNextIncomingKeys());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testIncrementStreamCounterForHandshakeKeys() throws Exception {
		long timePeriod = 123;
		SecretKey rootKey = getSecretKey();
		boolean alice = random.nextBoolean();
		TransportKeys keys = createHandshakeKeys(timePeriod, rootKey, alice);
		long streamCounter = keys.getCurrentOutgoingKeys().getStreamCounter();

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addTransport(txn, transportId, 123);
		assertEquals(keySetId, db.addTransportKeys(txn, contactId, keys));

		db.incrementStreamCounter(txn, transportId, keySetId);
		db.incrementStreamCounter(txn, transportId, keySetId);
		Collection<TransportKeySet> newKeys =
				db.getTransportKeys(txn, transportId);
		assertEquals(1, newKeys.size());
		TransportKeySet ks = newKeys.iterator().next();
		assertEquals(keySetId, ks.getKeySetId());
		assertEquals(contactId, ks.getContactId());
		TransportKeys k = ks.getKeys();
		assertEquals(transportId, k.getTransportId());
		assertNotNull(k.getRootKey());
		assertArrayEquals(rootKey.getBytes(), k.getRootKey().getBytes());
		assertEquals(alice, k.isAlice());
		OutgoingKeys outCurr = k.getCurrentOutgoingKeys();
		assertEquals(timePeriod, outCurr.getTimePeriod());
		assertEquals(streamCounter + 2, outCurr.getStreamCounter());

		assertKeysEquals(keys.getPreviousIncomingKeys(),
				k.getPreviousIncomingKeys());
		assertKeysEquals(keys.getCurrentIncomingKeys(),
				k.getCurrentIncomingKeys());
		assertKeysEquals(keys.getNextIncomingKeys(), k.getNextIncomingKeys());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testSetReorderingWindow() throws Exception {
		boolean active = random.nextBoolean();
		long timePeriod = 123;
		TransportKeys keys = createTransportKeys(timePeriod, active);
		long base = keys.getCurrentIncomingKeys().getWindowBase();
		byte[] bitmap = keys.getCurrentIncomingKeys().getWindowBitmap();

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addTransport(txn, transportId, 123);
		assertEquals(keySetId, db.addTransportKeys(txn, contactId, keys));

		random.nextBytes(bitmap);
		db.setReorderingWindow(txn, keySetId, transportId, timePeriod,
				base + 1, bitmap);
		Collection<TransportKeySet> newKeys =
				db.getTransportKeys(txn, transportId);
		assertEquals(1, newKeys.size());
		TransportKeySet ks = newKeys.iterator().next();
		assertEquals(keySetId, ks.getKeySetId());
		assertEquals(contactId, ks.getContactId());
		TransportKeys k = ks.getKeys();
		assertEquals(transportId, k.getTransportId());
		IncomingKeys inCurr = k.getCurrentIncomingKeys();
		assertEquals(timePeriod, inCurr.getTimePeriod());
		assertEquals(base + 1, inCurr.getWindowBase());
		assertArrayEquals(bitmap, inCurr.getWindowBitmap());

		assertKeysEquals(keys.getPreviousIncomingKeys(),
				k.getPreviousIncomingKeys());
		assertKeysEquals(keys.getNextIncomingKeys(), k.getNextIncomingKeys());
		assertKeysEquals(keys.getCurrentOutgoingKeys(),
				k.getCurrentOutgoingKeys());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testSetReorderingWindowForHandshakeKeys() throws Exception {
		long timePeriod = 123;
		SecretKey rootKey = getSecretKey();
		boolean alice = random.nextBoolean();
		TransportKeys keys = createHandshakeKeys(timePeriod, rootKey, alice);
		long base = keys.getCurrentIncomingKeys().getWindowBase();
		byte[] bitmap = keys.getCurrentIncomingKeys().getWindowBitmap();

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addTransport(txn, transportId, 123);
		assertEquals(keySetId, db.addTransportKeys(txn, contactId, keys));

		random.nextBytes(bitmap);
		db.setReorderingWindow(txn, keySetId, transportId, timePeriod,
				base + 1, bitmap);
		Collection<TransportKeySet> newKeys =
				db.getTransportKeys(txn, transportId);
		assertEquals(1, newKeys.size());
		TransportKeySet ks = newKeys.iterator().next();
		assertEquals(keySetId, ks.getKeySetId());
		assertEquals(contactId, ks.getContactId());
		TransportKeys k = ks.getKeys();
		assertEquals(transportId, k.getTransportId());
		assertNotNull(k.getRootKey());
		assertArrayEquals(rootKey.getBytes(), k.getRootKey().getBytes());
		assertEquals(alice, k.isAlice());
		IncomingKeys inCurr = k.getCurrentIncomingKeys();
		assertEquals(timePeriod, inCurr.getTimePeriod());
		assertEquals(base + 1, inCurr.getWindowBase());
		assertArrayEquals(bitmap, inCurr.getWindowBitmap());

		assertKeysEquals(keys.getPreviousIncomingKeys(),
				k.getPreviousIncomingKeys());
		assertKeysEquals(keys.getNextIncomingKeys(), k.getNextIncomingKeys());
		assertKeysEquals(keys.getCurrentOutgoingKeys(),
				k.getCurrentOutgoingKeys());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testGetContactsByAuthorId() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);

		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));

		Collection<Contact> contacts =
				db.getContactsByAuthorId(txn, author.getId());
		assertEquals(1, contacts.size());
		assertEquals(contactId, contacts.iterator().next().getId());

		db.removeContact(txn, contactId);
		contacts = db.getContactsByAuthorId(txn, author.getId());
		assertEquals(0, contacts.size());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testGetContactsByLocalAuthorId() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		Collection<ContactId> contacts =
				db.getContacts(txn, localAuthor.getId());
		assertEquals(emptyList(), contacts);

		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		contacts = db.getContacts(txn, localAuthor.getId());
		assertEquals(singletonList(contactId), contacts);

		db.removeIdentity(txn, localAuthor.getId());
		contacts = db.getContacts(txn, localAuthor.getId());
		assertEquals(emptyList(), contacts);
		assertFalse(db.containsContact(txn, contactId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testGetContactsByHandshakePublicKey() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		PublicKey handshakePublicKey = TestUtils.getSignaturePublicKey();
		Contact contact =
				db.getContact(txn, handshakePublicKey, localAuthor.getId());
		assertNull(contact);

		assertEquals(contactId, db.addContact(txn, author, localAuthor.getId(),
				handshakePublicKey, true));
		contact = db.getContact(txn, handshakePublicKey, localAuthor.getId());
		assertNotNull(contact);
		assertEquals(contactId, contact.getId());
		assertEquals(author, contact.getAuthor());
		assertNull(contact.getAlias());
		assertEquals(handshakePublicKey, contact.getHandshakePublicKey());
		assertTrue(contact.isVerified());
		assertEquals(author.getName(), contact.getAuthor().getName());
		assertEquals(author.getPublicKey(), contact.getAuthor().getPublicKey());
		assertEquals(author.getFormatVersion(),
				contact.getAuthor().getFormatVersion());

		db.removeContact(txn, contactId);
		contact = db.getContact(txn, handshakePublicKey, localAuthor.getId());
		assertNull(contact);

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testOfferedMessages() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		assertEquals(0, db.countOfferedMessages(txn, contactId));

		List<MessageId> ids = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			MessageId m = new MessageId(getRandomId());
			db.addOfferedMessage(txn, contactId, m);
			ids.add(m);
		}
		assertEquals(10, db.countOfferedMessages(txn, contactId));

		List<MessageId> half = ids.subList(0, 5);
		db.removeOfferedMessages(txn, contactId, half);
		assertEquals(5, db.countOfferedMessages(txn, contactId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testGroupMetadata() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);

		Metadata metadata = new Metadata();
		metadata.put("foo", new byte[] {'b', 'a', 'r'});
		metadata.put("baz", new byte[] {'b', 'a', 'm'});
		db.mergeGroupMetadata(txn, groupId, metadata);

		Metadata retrieved = db.getGroupMetadata(txn, groupId);
		assertEquals(2, retrieved.size());
		assertTrue(retrieved.containsKey("foo"));
		assertArrayEquals(metadata.get("foo"), retrieved.get("foo"));
		assertTrue(retrieved.containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), retrieved.get("baz"));

		metadata.put("foo", REMOVE);
		metadata.put("baz", new byte[] {'q', 'u', 'x'});
		db.mergeGroupMetadata(txn, groupId, metadata);

		retrieved = db.getGroupMetadata(txn, groupId);
		assertEquals(1, retrieved.size());
		assertFalse(retrieved.containsKey("foo"));
		assertTrue(retrieved.containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), retrieved.get("baz"));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testMessageMetadata() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		Metadata metadata = new Metadata();
		metadata.put("foo", new byte[] {'b', 'a', 'r'});
		metadata.put("baz", new byte[] {'b', 'a', 'm'});
		db.mergeMessageMetadata(txn, messageId, metadata);

		Metadata retrieved = db.getMessageMetadata(txn, messageId);
		assertEquals(2, retrieved.size());
		assertTrue(retrieved.containsKey("foo"));
		assertArrayEquals(metadata.get("foo"), retrieved.get("foo"));
		assertTrue(retrieved.containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), retrieved.get("baz"));

		Map<MessageId, Metadata> all = db.getMessageMetadata(txn, groupId);
		assertEquals(1, all.size());
		assertTrue(all.containsKey(messageId));
		retrieved = all.get(messageId);
		assertEquals(2, retrieved.size());
		assertTrue(retrieved.containsKey("foo"));
		assertArrayEquals(metadata.get("foo"), retrieved.get("foo"));
		assertTrue(retrieved.containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), retrieved.get("baz"));

		metadata.put("foo", REMOVE);
		metadata.put("baz", new byte[] {'q', 'u', 'x'});
		db.mergeMessageMetadata(txn, messageId, metadata);

		retrieved = db.getMessageMetadata(txn, messageId);
		assertEquals(1, retrieved.size());
		assertFalse(retrieved.containsKey("foo"));
		assertTrue(retrieved.containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), retrieved.get("baz"));

		all = db.getMessageMetadata(txn, groupId);
		assertEquals(1, all.size());
		assertTrue(all.containsKey(messageId));
		retrieved = all.get(messageId);
		assertEquals(1, retrieved.size());
		assertFalse(retrieved.containsKey("foo"));
		assertTrue(retrieved.containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), retrieved.get("baz"));

		db.deleteMessageMetadata(txn, messageId);

		retrieved = db.getMessageMetadata(txn, messageId);
		assertTrue(retrieved.isEmpty());

		all = db.getMessageMetadata(txn, groupId);
		assertTrue(all.isEmpty());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testMessageMetadataOnlyForDeliveredMessages() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		Metadata metadata = new Metadata();
		metadata.put("foo", new byte[] {'b', 'a', 'r'});
		metadata.put("baz", new byte[] {'b', 'a', 'm'});
		db.mergeMessageMetadata(txn, messageId, metadata);

		Metadata retrieved = db.getMessageMetadata(txn, messageId);
		assertEquals(2, retrieved.size());
		assertTrue(retrieved.containsKey("foo"));
		assertArrayEquals(metadata.get("foo"), retrieved.get("foo"));
		assertTrue(retrieved.containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), retrieved.get("baz"));
		Map<MessageId, Metadata> map = db.getMessageMetadata(txn, groupId);
		assertEquals(1, map.size());
		assertTrue(map.get(messageId).containsKey("foo"));
		assertArrayEquals(metadata.get("foo"), map.get(messageId).get("foo"));
		assertTrue(map.get(messageId).containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), map.get(messageId).get("baz"));

		db.setMessageState(txn, messageId, UNKNOWN);
		retrieved = db.getMessageMetadata(txn, messageId);
		assertTrue(retrieved.isEmpty());
		map = db.getMessageMetadata(txn, groupId);
		assertTrue(map.isEmpty());

		db.setMessageState(txn, messageId, INVALID);
		retrieved = db.getMessageMetadata(txn, messageId);
		assertTrue(retrieved.isEmpty());
		map = db.getMessageMetadata(txn, groupId);
		assertTrue(map.isEmpty());

		db.setMessageState(txn, messageId, PENDING);
		retrieved = db.getMessageMetadata(txn, messageId);
		assertTrue(retrieved.isEmpty());
		map = db.getMessageMetadata(txn, groupId);
		assertTrue(map.isEmpty());

		retrieved = db.getMessageMetadataForValidator(txn, messageId);
		assertFalse(retrieved.isEmpty());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testMetadataQueries() throws Exception {
		Message message1 = getMessage(groupId);
		MessageId messageId1 = message1.getId();

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);
		db.addMessage(txn, message, DELIVERED, true, false, null);
		db.addMessage(txn, message1, DELIVERED, true, false, null);

		Metadata metadata = new Metadata();
		metadata.put("foo", new byte[] {'b', 'a', 'r'});
		metadata.put("baz", new byte[] {'b', 'a', 'm'});
		db.mergeMessageMetadata(txn, messageId, metadata);
		Metadata metadata1 = new Metadata();
		metadata1.put("foo", new byte[] {'q', 'u', 'x'});
		db.mergeMessageMetadata(txn, messageId1, metadata1);

		Map<MessageId, Metadata> all = db.getMessageMetadata(txn, groupId);
		assertEquals(2, all.size());
		assertTrue(all.containsKey(messageId));
		assertTrue(all.containsKey(messageId1));
		Metadata retrieved = all.get(messageId);
		assertEquals(2, retrieved.size());
		assertTrue(retrieved.containsKey("foo"));
		assertArrayEquals(metadata.get("foo"), retrieved.get("foo"));
		assertTrue(retrieved.containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), retrieved.get("baz"));
		retrieved = all.get(messageId1);
		assertEquals(1, retrieved.size());
		assertTrue(retrieved.containsKey("foo"));
		assertArrayEquals(metadata1.get("foo"), retrieved.get("foo"));

		Metadata query = new Metadata();
		all = db.getMessageMetadata(txn, groupId, query);
		assertEquals(2, all.size());
		assertTrue(all.containsKey(messageId));
		assertTrue(all.containsKey(messageId1));
		retrieved = all.get(messageId);
		assertEquals(2, retrieved.size());
		assertTrue(retrieved.containsKey("foo"));
		assertArrayEquals(metadata.get("foo"), retrieved.get("foo"));
		assertTrue(retrieved.containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), retrieved.get("baz"));
		retrieved = all.get(messageId1);
		assertEquals(1, retrieved.size());
		assertTrue(retrieved.containsKey("foo"));
		assertArrayEquals(metadata1.get("foo"), retrieved.get("foo"));

		query = new Metadata();
		query.put("foo", metadata.get("foo"));
		all = db.getMessageMetadata(txn, groupId, query);
		assertEquals(1, all.size());
		assertTrue(all.containsKey(messageId));
		retrieved = all.get(messageId);
		assertEquals(2, retrieved.size());
		assertTrue(retrieved.containsKey("foo"));
		assertArrayEquals(metadata.get("foo"), retrieved.get("foo"));
		assertTrue(retrieved.containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), retrieved.get("baz"));

		query = new Metadata();
		query.put("foo", metadata1.get("foo"));
		all = db.getMessageMetadata(txn, groupId, query);
		assertEquals(1, all.size());
		assertTrue(all.containsKey(messageId1));
		retrieved = all.get(messageId1);
		assertEquals(1, retrieved.size());
		assertTrue(retrieved.containsKey("foo"));
		assertArrayEquals(metadata1.get("foo"), retrieved.get("foo"));

		query = new Metadata();
		query.put("foo", metadata.get("foo"));
		query.put("baz", metadata.get("baz"));
		all = db.getMessageMetadata(txn, groupId, query);
		assertEquals(1, all.size());
		assertTrue(all.containsKey(messageId));
		retrieved = all.get(messageId);
		assertEquals(2, retrieved.size());
		assertTrue(retrieved.containsKey("foo"));
		assertArrayEquals(metadata.get("foo"), retrieved.get("foo"));
		assertTrue(retrieved.containsKey("baz"));
		assertArrayEquals(metadata.get("baz"), retrieved.get("baz"));

		query = new Metadata();
		query.put("foo", metadata1.get("foo"));
		query.put("baz", metadata.get("baz"));
		all = db.getMessageMetadata(txn, groupId, query);
		assertTrue(all.isEmpty());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testMetadataQueriesOnlyForDeliveredMessages() throws Exception {
		Message message1 = getMessage(groupId);
		MessageId messageId1 = message1.getId();

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);
		db.addMessage(txn, message, DELIVERED, true, false, null);
		db.addMessage(txn, message1, DELIVERED, true, false, null);

		Metadata metadata = new Metadata();
		metadata.put("foo", new byte[] {'b', 'a', 'r'});
		metadata.put("baz", new byte[] {'b', 'a', 'm'});
		db.mergeMessageMetadata(txn, messageId, metadata);
		Metadata metadata1 = new Metadata();
		metadata1.put("foo", new byte[] {'b', 'a', 'r'});
		db.mergeMessageMetadata(txn, messageId1, metadata1);

		for (int i = 0; i < 2; i++) {
			Metadata query;
			if (i == 0) {

				query = new Metadata();
			} else {

				query = new Metadata();
				query.put("foo", new byte[] {'b', 'a', 'r'});
			}

			db.setMessageState(txn, messageId, DELIVERED);
			db.setMessageState(txn, messageId1, DELIVERED);
			Map<MessageId, Metadata> all =
					db.getMessageMetadata(txn, groupId, query);
			assertEquals(2, all.size());
			assertMetadataEquals(metadata, all.get(messageId));
			assertMetadataEquals(metadata1, all.get(messageId1));

			db.setMessageState(txn, messageId, UNKNOWN);
			all = db.getMessageMetadata(txn, groupId, query);
			assertEquals(1, all.size());
			assertMetadataEquals(metadata1, all.get(messageId1));

			db.setMessageState(txn, messageId, INVALID);
			all = db.getMessageMetadata(txn, groupId, query);
			assertEquals(1, all.size());
			assertMetadataEquals(metadata1, all.get(messageId1));

			db.setMessageState(txn, messageId, PENDING);
			all = db.getMessageMetadata(txn, groupId, query);
			assertEquals(1, all.size());
			assertMetadataEquals(metadata1, all.get(messageId1));
		}

		db.commitTransaction(txn);
		db.close();
	}

	private void assertMetadataEquals(Metadata m1, Metadata m2) {
		assertEquals(m1.keySet(), m2.keySet());
		for (Entry<String, byte[]> e : m1.entrySet()) {
			assertArrayEquals(e.getValue(), m2.get(e.getKey()));
		}
	}

	@Test
	public void testMessageDependencies() throws Exception {
		Message message1 = getMessage(groupId);
		Message message2 = getMessage(groupId);
		Message message3 = getMessage(groupId);
		Message message4 = getMessage(groupId);
		MessageId messageId1 = message1.getId();
		MessageId messageId2 = message2.getId();
		MessageId messageId3 = message3.getId();
		MessageId messageId4 = message4.getId();

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);
		db.addMessage(txn, message, PENDING, true, false, contactId);
		db.addMessage(txn, message1, PENDING, true, false, contactId);
		db.addMessage(txn, message2, INVALID, true, false, contactId);

		db.addMessageDependency(txn, message, messageId1, PENDING);
		db.addMessageDependency(txn, message, messageId2, PENDING);
		db.addMessageDependency(txn, message1, messageId3, PENDING);
		db.addMessageDependency(txn, message2, messageId4, INVALID);

		Map<MessageId, MessageState> dependencies;

		dependencies = db.getMessageDependencies(txn, messageId);
		assertEquals(2, dependencies.size());
		assertEquals(PENDING, dependencies.get(messageId1));
		assertEquals(INVALID, dependencies.get(messageId2));

		dependencies = db.getMessageDependencies(txn, messageId1);
		assertEquals(1, dependencies.size());
		assertEquals(UNKNOWN, dependencies.get(messageId3));

		dependencies = db.getMessageDependencies(txn, messageId2);
		assertEquals(1, dependencies.size());
		assertEquals(UNKNOWN, dependencies.get(messageId4));

		dependencies = db.getMessageDependencies(txn, messageId3);
		assertEquals(0, dependencies.size());
		dependencies = db.getMessageDependencies(txn, messageId4);
		assertEquals(0, dependencies.size());

		Map<MessageId, MessageState> dependents;

		dependents = db.getMessageDependents(txn, messageId);
		assertEquals(0, dependents.size());

		dependents = db.getMessageDependents(txn, messageId1);
		assertEquals(1, dependents.size());
		assertEquals(PENDING, dependents.get(messageId));
		dependents = db.getMessageDependents(txn, messageId2);
		assertEquals(1, dependents.size());
		assertEquals(PENDING, dependents.get(messageId));

		dependents = db.getMessageDependents(txn, messageId3);
		assertEquals(0, dependents.size());

		db.addMessage(txn, message3, UNKNOWN, false, false, contactId);

		dependents = db.getMessageDependents(txn, messageId3);
		assertEquals(1, dependents.size());
		assertEquals(PENDING, dependents.get(messageId1));

		dependents = db.getMessageDependents(txn, messageId4);
		assertEquals(0, dependents.size());

		db.addMessage(txn, message4, UNKNOWN, false, false, contactId);

		dependents = db.getMessageDependents(txn, messageId4);
		assertEquals(1, dependents.size());
		assertEquals(INVALID, dependents.get(messageId2));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testMessageDependenciesAcrossGroups() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);
		db.addMessage(txn, message, PENDING, true, false, contactId);

		Group group1 = getGroup(clientId, 123);
		GroupId groupId1 = group1.getId();
		db.addGroup(txn, group1);

		Message message1 = getMessage(groupId1);
		MessageId messageId1 = message1.getId();
		db.addMessage(txn, message1, DELIVERED, true, false, contactId);

		MessageId messageId2 = new MessageId(getRandomId());

		Message message3 = getMessage(groupId);
		MessageId messageId3 = message3.getId();
		db.addMessage(txn, message3, DELIVERED, true, false, contactId);

		db.addMessageDependency(txn, message, messageId1, PENDING);
		db.addMessageDependency(txn, message, messageId2, PENDING);
		db.addMessageDependency(txn, message, messageId3, PENDING);

		Map<MessageId, MessageState> dependencies;
		dependencies = db.getMessageDependencies(txn, messageId);

		assertEquals(UNKNOWN, dependencies.get(messageId1));

		assertEquals(UNKNOWN, dependencies.get(messageId2));

		assertEquals(DELIVERED, dependencies.get(messageId3));

		Map<MessageId, MessageState> dependents;
		dependents = db.getMessageDependents(txn, messageId1);

		assertFalse(dependents.containsKey(messageId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testGetPendingMessagesForDelivery() throws Exception {
		Message message1 = getMessage(groupId);
		Message message2 = getMessage(groupId);
		Message message3 = getMessage(groupId);
		Message message4 = getMessage(groupId);

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);
		db.addMessage(txn, message1, UNKNOWN, true, false, contactId);
		db.addMessage(txn, message2, INVALID, true, false, contactId);
		db.addMessage(txn, message3, PENDING, true, false, contactId);
		db.addMessage(txn, message4, DELIVERED, true, false, contactId);

		Collection<MessageId> result;

		result = db.getMessagesToValidate(txn);
		assertEquals(1, result.size());
		assertTrue(result.contains(message1.getId()));

		result = db.getPendingMessages(txn);
		assertEquals(1, result.size());
		assertTrue(result.contains(message3.getId()));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testGetMessagesToShare() throws Exception {
		Message message1 = getMessage(groupId);
		Message message2 = getMessage(groupId);
		Message message3 = getMessage(groupId);
		Message message4 = getMessage(groupId);

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);
		db.addMessage(txn, message1, DELIVERED, true, false, contactId);
		db.addMessage(txn, message2, DELIVERED, false, false, contactId);
		db.addMessage(txn, message3, DELIVERED, false, false, contactId);
		db.addMessage(txn, message4, DELIVERED, true, false, contactId);

		db.addMessageDependency(txn, message1, message2.getId(), DELIVERED);
		db.addMessageDependency(txn, message3, message1.getId(), DELIVERED);
		db.addMessageDependency(txn, message4, message3.getId(), DELIVERED);

		Collection<MessageId> result = db.getMessagesToShare(txn);
		assertEquals(2, result.size());
		assertTrue(result.contains(message2.getId()));
		assertTrue(result.contains(message3.getId()));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testGetMessageStatus() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		MessageStatus status = db.getMessageStatus(txn, contactId, messageId);
		assertNotNull(status);
		assertEquals(messageId, status.getMessageId());
		assertEquals(contactId, status.getContactId());
		assertFalse(status.isSent());
		assertFalse(status.isSeen());

		Collection<MessageStatus> statuses = db.getMessageStatus(txn,
				contactId, groupId);
		assertEquals(1, statuses.size());
		status = statuses.iterator().next();
		assertEquals(messageId, status.getMessageId());
		assertEquals(contactId, status.getContactId());
		assertFalse(status.isSent());
		assertFalse(status.isSeen());

		db.updateRetransmissionData(txn, contactId, messageId,
				Integer.MAX_VALUE);

		status = db.getMessageStatus(txn, contactId, messageId);
		assertNotNull(status);
		assertEquals(messageId, status.getMessageId());
		assertEquals(contactId, status.getContactId());
		assertTrue(status.isSent());
		assertFalse(status.isSeen());

		statuses = db.getMessageStatus(txn, contactId, groupId);
		assertEquals(1, statuses.size());
		status = statuses.iterator().next();
		assertEquals(messageId, status.getMessageId());
		assertEquals(contactId, status.getContactId());
		assertTrue(status.isSent());
		assertFalse(status.isSeen());

		db.raiseSeenFlag(txn, contactId, messageId);

		status = db.getMessageStatus(txn, contactId, messageId);
		assertNotNull(status);
		assertEquals(messageId, status.getMessageId());
		assertEquals(contactId, status.getContactId());
		assertTrue(status.isSent());
		assertTrue(status.isSeen());

		statuses = db.getMessageStatus(txn, contactId, groupId);
		assertEquals(1, statuses.size());
		status = statuses.iterator().next();
		assertEquals(messageId, status.getMessageId());
		assertEquals(contactId, status.getContactId());
		assertTrue(status.isSent());
		assertTrue(status.isSeen());

		db.removeGroupVisibility(txn, contactId, groupId);

		assertNull(db.getMessageStatus(txn, contactId, messageId));

		statuses = db.getMessageStatus(txn, contactId, groupId);
		assertEquals(0, statuses.size());

		db.addGroupVisibility(txn, contactId, groupId, false);

		status = db.getMessageStatus(txn, contactId, messageId);
		assertNotNull(status);
		assertEquals(messageId, status.getMessageId());
		assertEquals(contactId, status.getContactId());
		assertFalse(status.isSent());
		assertFalse(status.isSeen());

		statuses = db.getMessageStatus(txn, contactId, groupId);
		assertEquals(1, statuses.size());
		status = statuses.iterator().next();
		assertEquals(messageId, status.getMessageId());
		assertEquals(contactId, status.getContactId());
		assertFalse(status.isSent());
		assertFalse(status.isSeen());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testDifferentLocalAuthorsCanHaveTheSameContact()
			throws Exception {
		Identity identity1 = getIdentity();
		LocalAuthor localAuthor1 = identity1.getLocalAuthor();

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		db.addIdentity(txn, identity1);

		ContactId contactId =
				db.addContact(txn, author, localAuthor.getId(), null, true);
		ContactId contactId1 =
				db.addContact(txn, author, localAuthor1.getId(), null, true);

		assertNotEquals(contactId, contactId1);
		assertEquals(2, db.getContacts(txn).size());
		assertEquals(1, db.getContacts(txn, localAuthor.getId()).size());
		assertEquals(1, db.getContacts(txn, localAuthor1.getId()).size());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testDeleteMessage() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		assertTrue(db.containsVisibleMessage(txn, contactId, messageId));

		assertOneMessageToSendLazily(db, txn);
		assertOneMessageToSendEagerly(db, txn);

		Message m = db.getMessage(txn, messageId);
		assertEquals(messageId, m.getId());
		assertEquals(groupId, m.getGroupId());
		assertEquals(message.getTimestamp(), m.getTimestamp());
		assertArrayEquals(message.getBody(), m.getBody());

		db.deleteMessage(txn, messageId);

		assertTrue(db.containsVisibleMessage(txn, contactId, messageId));

		assertNothingToSendLazily(db, txn);
		assertNothingToSendEagerly(db, txn);

		try {
			db.getMessage(txn, messageId);
			fail();
		} catch (MessageDeletedException expected) {

		}

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testSetContactAlias() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));

		Contact contact = db.getContact(txn, contactId);
		assertNull(contact.getAlias());

		String alias = getRandomString(MAX_AUTHOR_NAME_LENGTH);
		db.setContactAlias(txn, contactId, alias);

		contact = db.getContact(txn, contactId);
		assertEquals(alias, contact.getAlias());

		db.setContactAlias(txn, contactId, null);

		contact = db.getContact(txn, contactId);
		assertNull(contact.getAlias());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testSetMessageState() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);
		db.addMessage(txn, message, UNKNOWN, false, false, contactId);

		assertEquals(UNKNOWN, db.getMessageState(txn, messageId));
		db.setMessageState(txn, messageId, INVALID);
		assertEquals(INVALID, db.getMessageState(txn, messageId));
		db.setMessageState(txn, messageId, PENDING);
		assertEquals(PENDING, db.getMessageState(txn, messageId));
		db.setMessageState(txn, messageId, DELIVERED);
		assertEquals(DELIVERED, db.getMessageState(txn, messageId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testGetNextSendTime() throws Exception {
		long now = System.currentTimeMillis();
		Database<Connection> db = open(false, new TestMessageFactory(),
				new StoppedClock(now));
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addMessage(txn, message, UNKNOWN, false, false, null);

		assertEquals(Long.MAX_VALUE,
				db.getNextSendTime(txn, contactId, MAX_LATENCY));

		db.addGroupVisibility(txn, contactId, groupId, true);
		assertEquals(Long.MAX_VALUE,
				db.getNextSendTime(txn, contactId, MAX_LATENCY));

		db.setMessageState(txn, messageId, DELIVERED);
		assertEquals(Long.MAX_VALUE,
				db.getNextSendTime(txn, contactId, MAX_LATENCY));

		db.setMessageShared(txn, messageId, true);
		assertEquals(0, db.getNextSendTime(txn, contactId, MAX_LATENCY));

		db.raiseRequestedFlag(txn, contactId, messageId);
		assertEquals(0, db.getNextSendTime(txn, contactId, MAX_LATENCY));

		db.updateRetransmissionData(txn, contactId, messageId, MAX_LATENCY);
		assertEquals(now + MAX_LATENCY * 2,
				db.getNextSendTime(txn, contactId, MAX_LATENCY));

		assertEquals(0L, db.getNextSendTime(txn, contactId, MAX_LATENCY - 1));

		db.updateRetransmissionData(txn, contactId, messageId, MAX_LATENCY);
		assertEquals(now + MAX_LATENCY * 4,
				db.getNextSendTime(txn, contactId, MAX_LATENCY));

		assertEquals(0L, db.getNextSendTime(txn, contactId, MAX_LATENCY - 1));

		db.deleteMessage(txn, messageId);
		assertEquals(Long.MAX_VALUE,
				db.getNextSendTime(txn, contactId, MAX_LATENCY));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testGetGroups() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		assertEquals(emptyList(), db.getGroups(txn, clientId, majorVersion));
		db.addGroup(txn, group);
		assertEquals(singletonList(group),
				db.getGroups(txn, clientId, majorVersion));
		db.removeGroup(txn, groupId);
		assertEquals(emptyList(), db.getGroups(txn, clientId, majorVersion));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testExceptionHandling() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();
		try {

			db.getMessage(txn, messageId);
			fail();
		} catch (DbException expected) {

			db.abortTransaction(txn);
		}

		db.close();
	}

	@Test
	public void testMessageRetransmission() throws Exception {
		long now = System.currentTimeMillis();
		AtomicLong time = new AtomicLong(now);
		Database<Connection> db =
				open(false, new TestMessageFactory(), new SettableClock(time));
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		Collection<MessageId> ids = db.getMessagesToSend(txn, contactId,
				ONE_MEGABYTE, MAX_LATENCY);
		assertEquals(singletonList(messageId), ids);

		db.updateRetransmissionData(txn, contactId, messageId, MAX_LATENCY);

		assertEquals(now + MAX_LATENCY * 2,
				db.getNextSendTime(txn, contactId, MAX_LATENCY));

		time.set(now + MAX_LATENCY * 2 - 1);
		ids = db.getMessagesToSend(txn, contactId, ONE_MEGABYTE, MAX_LATENCY);
		assertTrue(ids.isEmpty());

		time.set(now + MAX_LATENCY * 2);
		ids = db.getMessagesToSend(txn, contactId, ONE_MEGABYTE, MAX_LATENCY);
		assertEquals(singletonList(messageId), ids);

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testFasterMessageRetransmission() throws Exception {
		long now = System.currentTimeMillis();
		AtomicLong time = new AtomicLong(now);
		Database<Connection> db =
				open(false, new TestMessageFactory(), new SettableClock(time));
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		Collection<MessageId> ids = db.getMessagesToSend(txn, contactId,
				ONE_MEGABYTE, MAX_LATENCY);
		assertEquals(singletonList(messageId), ids);

		db.updateRetransmissionData(txn, contactId, messageId, MAX_LATENCY);

		assertEquals(now + MAX_LATENCY * 2,
				db.getNextSendTime(txn, contactId, MAX_LATENCY));

		ids = db.getMessagesToSend(txn, contactId, ONE_MEGABYTE, MAX_LATENCY);
		assertTrue(ids.isEmpty());

		ids = db.getMessagesToSend(txn, contactId, ONE_MEGABYTE,
				MAX_LATENCY - 1);
		assertEquals(singletonList(messageId), ids);

		ids = db.getMessagesToSend(txn, contactId, ONE_MEGABYTE,
				MAX_LATENCY + 1);
		assertTrue(ids.isEmpty());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testResetRetransmissionTimes() throws Exception {
		long now = System.currentTimeMillis();
		AtomicLong time = new AtomicLong(now);
		Database<Connection> db =
				open(false, new TestMessageFactory(), new SettableClock(time));
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);
		db.addMessage(txn, message, DELIVERED, true, false, null);

		Collection<MessageId> ids = db.getMessagesToSend(txn, contactId,
				ONE_MEGABYTE, MAX_LATENCY);
		assertEquals(singletonList(messageId), ids);

		db.updateRetransmissionData(txn, contactId, messageId, MAX_LATENCY);

		assertEquals(now + MAX_LATENCY * 2,
				db.getNextSendTime(txn, contactId, MAX_LATENCY));

		time.set(now + MAX_LATENCY * 2 - 1);
		ids = db.getMessagesToSend(txn, contactId, ONE_MEGABYTE, MAX_LATENCY);
		assertTrue(ids.isEmpty());

		db.resetUnackedMessagesToSend(txn, contactId);

		assertEquals(0, db.getNextSendTime(txn, contactId, MAX_LATENCY));

		ids = db.getMessagesToSend(txn, contactId, ONE_MEGABYTE, MAX_LATENCY);
		assertFalse(ids.isEmpty());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testPendingContacts() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		assertEquals(emptyList(), db.getPendingContacts(txn));

		db.addPendingContact(txn, pendingContact);
		PendingContact retrieved =
				db.getPendingContact(txn, pendingContact.getId());
		assertPendingContactEquals(pendingContact, retrieved);

		Collection<PendingContact> pendingContacts = db.getPendingContacts(txn);
		assertEquals(1, pendingContacts.size());
		retrieved = pendingContacts.iterator().next();
		assertPendingContactEquals(pendingContact, retrieved);

		db.removePendingContact(txn, pendingContact.getId());
		assertEquals(emptyList(), db.getPendingContacts(txn));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testHybridPendingContactRoundTrip() throws Exception {
		byte[] blob = getRandomBytes(
				HYBRID_COMMITMENT_BYTES + HYBRID_RENDEZVOUS_X25519_BYTES);
		PublicKey commitmentKey = new HybridCommitmentPublicKey(blob);
		PendingContact hybrid = new PendingContact(
				new PendingContactId(getRandomId()), commitmentKey,
				"hybrid", 1234567890L, FORMAT_VERSION_HYBRID);

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addPendingContact(txn, hybrid);
		PendingContact retrieved = db.getPendingContact(txn, hybrid.getId());
		assertPendingContactEquals(hybrid, retrieved);
		assertEquals(FORMAT_VERSION_HYBRID, retrieved.getFormatVersion());

		Collection<PendingContact> all = db.getPendingContacts(txn);
		assertEquals(1, all.size());
		assertPendingContactEquals(hybrid, all.iterator().next());

		db.removePendingContact(txn, hybrid.getId());
		db.commitTransaction(txn);
		db.close();
	}

	private void assertPendingContactEquals(PendingContact expected,
			PendingContact actual) {
		assertEquals(expected.getId(), actual.getId());
		assertArrayEquals(expected.getPublicKey().getEncoded(),
				actual.getPublicKey().getEncoded());
		assertEquals(expected.getAlias(), actual.getAlias());
		assertEquals(expected.getTimestamp(), actual.getTimestamp());
	}

	@Test
	public void testSetHandshakeKeyPair() throws Exception {
		Identity withoutKeys = new Identity(localAuthor, null, null,
				identity.getTimeCreated());
		assertFalse(withoutKeys.hasHandshakeKeyPair());
		PublicKey publicKey = getAgreementPublicKey();
		PrivateKey privateKey = getAgreementPrivateKey();

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, withoutKeys);
		Identity retrieved = db.getIdentity(txn, localAuthor.getId());
		assertFalse(retrieved.hasHandshakeKeyPair());
		db.setHandshakeKeyPair(txn, localAuthor.getId(), publicKey, privateKey);
		retrieved = db.getIdentity(txn, localAuthor.getId());
		assertTrue(retrieved.hasHandshakeKeyPair());
		PublicKey handshakePub = retrieved.getHandshakePublicKey();
		assertNotNull(handshakePub);
		assertArrayEquals(publicKey.getEncoded(), handshakePub.getEncoded());
		PrivateKey handshakePriv = retrieved.getHandshakePrivateKey();
		assertNotNull(handshakePriv);
		assertArrayEquals(privateKey.getEncoded(), handshakePriv.getEncoded());

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testTemporaryMessages() throws Exception {
		Message message1 = getMessage(groupId);
		MessageId messageId1 = message1.getId();

		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addGroup(txn, group);
		db.addMessage(txn, message, DELIVERED, false, true, null);
		db.addMessage(txn, message1, DELIVERED, false, true, null);

		db.setMessagePermanent(txn, messageId);

		db.removeTemporaryMessages(txn);

		assertTrue(db.containsMessage(txn, messageId));

		assertFalse(db.containsMessage(txn, messageId1));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testSyncVersions() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));

		List<Byte> defaultSupported = singletonList((byte) 0);
		assertEquals(defaultSupported, db.getSyncVersions(txn, contactId));

		List<Byte> supported = asList((byte) 0, (byte) 1);
		db.setSyncVersions(txn, contactId, supported);
		assertEquals(supported, db.getSyncVersions(txn, contactId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testShutdownGracefully() throws Exception {
		Database<Connection> db = open(false);
		db.close();
		open(true);
		assertFalse(db.wasDirtyOnInitialisation());
	}

	@Test
	public void testShutdownDirty() throws Exception {
		Database<Connection> db = open(false);

		List<String> unloadedDrivers = unloadDrivers();

		try {
			db.close();
			fail();
		} catch (Exception e) {

			e.printStackTrace();
		}

		reloadDrivers(unloadedDrivers);

		db = open(true);
		assertTrue(db.wasDirtyOnInitialisation());
	}

	@Test
	public void testShutdownDirtyThenGracefully() throws Exception {
		Database<Connection> db = open(false);

		List<String> unloadedDrivers = unloadDrivers();

		try {
			db.close();
			fail();
		} catch (Exception e) {

		}

		reloadDrivers(unloadedDrivers);

		db = open(true);
		assertTrue(db.wasDirtyOnInitialisation());

		db.close();
		db = open(true);
		assertFalse(db.wasDirtyOnInitialisation());
	}

	@Test
	public void testCleanupTimer() throws Exception {
		long duration = 60_000;
		long now = System.currentTimeMillis();
		AtomicLong time = new AtomicLong(now);
		Database<Connection> db =
				open(false, new TestMessageFactory(), new SettableClock(time));
		Connection txn = db.startTransaction();

		assertTrue(db.getMessagesToDelete(txn).isEmpty());
		assertEquals(NO_CLEANUP_DEADLINE, db.getNextCleanupDeadline(txn));

		db.addGroup(txn, group);
		db.addMessage(txn, message, DELIVERED, false, false, null);

		assertTrue(db.getMessagesToDelete(txn).isEmpty());
		assertEquals(NO_CLEANUP_DEADLINE, db.getNextCleanupDeadline(txn));

		db.setCleanupTimerDuration(txn, messageId, duration);

		assertTrue(db.getMessagesToDelete(txn).isEmpty());
		assertEquals(NO_CLEANUP_DEADLINE, db.getNextCleanupDeadline(txn));

		assertEquals(now + duration, db.startCleanupTimer(txn, messageId));

		assertEquals(TIMER_NOT_STARTED, db.startCleanupTimer(txn, messageId));

		assertTrue(db.getMessagesToDelete(txn).isEmpty());
		assertEquals(now + duration, db.getNextCleanupDeadline(txn));

		db.stopCleanupTimer(txn, messageId);

		assertTrue(db.getMessagesToDelete(txn).isEmpty());
		assertEquals(NO_CLEANUP_DEADLINE, db.getNextCleanupDeadline(txn));

		assertEquals(now + duration, db.startCleanupTimer(txn, messageId));

		assertTrue(db.getMessagesToDelete(txn).isEmpty());
		assertEquals(now + duration, db.getNextCleanupDeadline(txn));

		time.set(now + duration - 1);
		assertTrue(db.getMessagesToDelete(txn).isEmpty());
		assertEquals(now + duration, db.getNextCleanupDeadline(txn));

		time.set(now + duration);
		assertEquals(singletonMap(groupId, singletonList(messageId)),
				db.getMessagesToDelete(txn));
		assertEquals(now + duration, db.getNextCleanupDeadline(txn));

		time.set(now + duration + 1);
		assertEquals(singletonMap(groupId, singletonList(messageId)),
				db.getMessagesToDelete(txn));
		assertEquals(now + duration, db.getNextCleanupDeadline(txn));

		db.deleteMessage(txn, messageId);
		assertTrue(db.getMessagesToDelete(txn).isEmpty());
		assertEquals(NO_CLEANUP_DEADLINE, db.getNextCleanupDeadline(txn));
	}

	@Test
	public void testRemoveAllGroupMessagesDeletesOffers() throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);

		db.addMessage(txn, message, DELIVERED, true, false, null);
		Message message2 = getMessage(groupId);
		db.addMessage(txn, message2, DELIVERED, true, false, null);

		db.addOfferedMessage(txn, contactId, messageId);
		db.addOfferedMessage(txn, contactId, message2.getId());
		assertEquals(2, db.countOfferedMessages(txn, contactId));

		db.removeAllGroupMessages(txn, groupId);

		assertFalse(db.containsMessage(txn, messageId));
		assertFalse(db.containsMessage(txn, message2.getId()));

		assertEquals(0, db.countOfferedMessages(txn, contactId));

		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testRemoveAllGroupMessagesPreservesOtherGroupOffers()
			throws Exception {
		Database<Connection> db = open(false);
		Connection txn = db.startTransaction();

		db.addIdentity(txn, identity);
		assertEquals(contactId,
				db.addContact(txn, author, localAuthor.getId(), null, true));
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, contactId, groupId, true);

		Group group2 = getGroup(clientId, majorVersion);
		db.addGroup(txn, group2);
		db.addGroupVisibility(txn, contactId, group2.getId(), true);

		db.addMessage(txn, message, DELIVERED, true, false, null);
		Message otherMsg = getMessage(group2.getId());
		db.addMessage(txn, otherMsg, DELIVERED, true, false, null);

		db.addOfferedMessage(txn, contactId, messageId);
		db.addOfferedMessage(txn, contactId, otherMsg.getId());
		assertEquals(2, db.countOfferedMessages(txn, contactId));

		db.removeAllGroupMessages(txn, groupId);

		assertFalse(db.containsMessage(txn, messageId));

		assertTrue(db.containsMessage(txn, otherMsg.getId()));
		assertEquals(1, db.countOfferedMessages(txn, contactId));

		db.commitTransaction(txn);
		db.close();
	}

	private Database<Connection> open(boolean resume) throws Exception {
		return open(resume, new TestMessageFactory(), new SystemClock());
	}

	private Database<Connection> open(boolean resume,
			MessageFactory messageFactory, Clock clock) throws Exception {
		Database<Connection> db = createDatabase(
				new TestDatabaseConfig(testDir), messageFactory, clock);
		if (!resume) deleteTestDirectory(testDir);
		db.open(key, null);
		return db;
	}

	private TransportKeys createTransportKeys(long timePeriod, boolean active) {
		SecretKey inPrevTagKey = getSecretKey();
		SecretKey inPrevHeaderKey = getSecretKey();
		IncomingKeys inPrev = new IncomingKeys(inPrevTagKey, inPrevHeaderKey,
				timePeriod - 1, 123, new byte[4]);
		SecretKey inCurrTagKey = getSecretKey();
		SecretKey inCurrHeaderKey = getSecretKey();
		IncomingKeys inCurr = new IncomingKeys(inCurrTagKey, inCurrHeaderKey,
				timePeriod, 234, new byte[4]);
		SecretKey inNextTagKey = getSecretKey();
		SecretKey inNextHeaderKey = getSecretKey();
		IncomingKeys inNext = new IncomingKeys(inNextTagKey, inNextHeaderKey,
				timePeriod + 1, 345, new byte[4]);
		SecretKey outCurrTagKey = getSecretKey();
		SecretKey outCurrHeaderKey = getSecretKey();
		OutgoingKeys outCurr = new OutgoingKeys(outCurrTagKey, outCurrHeaderKey,
				timePeriod, 456, active);
		return new TransportKeys(transportId, inPrev, inCurr, inNext, outCurr);
	}

	private TransportKeys createHandshakeKeys(long timePeriod,
			SecretKey rootKey, boolean alice) {
		SecretKey inPrevTagKey = getSecretKey();
		SecretKey inPrevHeaderKey = getSecretKey();
		IncomingKeys inPrev = new IncomingKeys(inPrevTagKey, inPrevHeaderKey,
				timePeriod - 1, 123, new byte[4]);
		SecretKey inCurrTagKey = getSecretKey();
		SecretKey inCurrHeaderKey = getSecretKey();
		IncomingKeys inCurr = new IncomingKeys(inCurrTagKey, inCurrHeaderKey,
				timePeriod, 234, new byte[4]);
		SecretKey inNextTagKey = getSecretKey();
		SecretKey inNextHeaderKey = getSecretKey();
		IncomingKeys inNext = new IncomingKeys(inNextTagKey, inNextHeaderKey,
				timePeriod + 1, 345, new byte[4]);
		SecretKey outCurrTagKey = getSecretKey();
		SecretKey outCurrHeaderKey = getSecretKey();
		OutgoingKeys outCurr = new OutgoingKeys(outCurrTagKey, outCurrHeaderKey,
				timePeriod, 456, true);
		return new TransportKeys(transportId, inPrev, inCurr, inNext, outCurr,
				rootKey, alice);
	}

	private List<String> unloadDrivers() {
		Enumeration<Driver> drivers = DriverManager.getDrivers();
		List<String> unloaded = new ArrayList<>();
		while (drivers.hasMoreElements()) {
			Driver d = drivers.nextElement();
			try {
				DriverManager.deregisterDriver(d);
				unloaded.add(d.getClass().getName());
			} catch (SQLException e) {
				e.printStackTrace();
				fail();
			}
		}
		return unloaded;
	}

	private void reloadDrivers(List<String> unloadedDrivers)
			throws ClassNotFoundException, IllegalAccessException,
			InstantiationException, SQLException {
		for (String driverName : unloadedDrivers) {
			DriverManager.registerDriver(
					(Driver) Class.forName(driverName).newInstance());
		}
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private void assertNothingToSendLazily(Database<Connection> db,
			Connection txn) throws Exception {
		assertFalse(
				db.containsMessagesToSend(txn, contactId, MAX_LATENCY, false));
		Collection<MessageId> ids =
				db.getMessagesToSend(txn, contactId, ONE_MEGABYTE, MAX_LATENCY);
		assertTrue(ids.isEmpty());
		ids = db.getMessagesToOffer(txn, contactId, 100, MAX_LATENCY);
		assertTrue(ids.isEmpty());
	}

	private void assertOneMessageToSendLazily(Database<Connection> db,
			Connection txn) throws Exception {
		assertTrue(
				db.containsMessagesToSend(txn, contactId, MAX_LATENCY, false));
		Collection<MessageId> ids =
				db.getMessagesToSend(txn, contactId, ONE_MEGABYTE, MAX_LATENCY);
		assertEquals(singletonList(messageId), ids);
		ids = db.getMessagesToOffer(txn, contactId, 100, MAX_LATENCY);
		assertEquals(singletonList(messageId), ids);
	}

	private void assertNothingToSendEagerly(Database<Connection> db,
			Connection txn) throws Exception {
		assertFalse(
				db.containsMessagesToSend(txn, contactId, MAX_LATENCY, true));
		Collection<MessageId> unacked =
				db.getUnackedMessagesToSend(txn, contactId);
		assertTrue(unacked.isEmpty());
		assertEquals(0, db.getUnackedMessageBytesToSend(txn, contactId));
	}

	private void assertOneMessageToSendEagerly(Database<Connection> db,
			Connection txn) throws Exception {
		assertTrue(
				db.containsMessagesToSend(txn, contactId, MAX_LATENCY, true));
		Collection<MessageId> unacked =
				db.getUnackedMessagesToSend(txn, contactId);
		assertEquals(singletonList(messageId), unacked);
		assertEquals(message.getRawLength(),
				db.getUnackedMessageBytesToSend(txn, contactId));
	}

	private static class StoppedClock implements Clock {

		private final long time;

		private StoppedClock(long time) {
			this.time = time;
		}

		@Override
		public long currentTimeMillis() {
			return time;
		}

		@Override
		public void sleep(long milliseconds) throws InterruptedException {
			Thread.sleep(milliseconds);
		}
	}
}

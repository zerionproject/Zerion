package org.zerionproject.core.db;

import org.zerionproject.core.api.cleanup.event.CleanupTimerStartedEvent;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.event.ContactAddedEvent;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.ContactExistsException;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.NoSuchGroupException;
import org.zerionproject.core.api.db.NoSuchIdentityException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.NoSuchPendingContactException;
import org.zerionproject.core.api.db.NoSuchTransportException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.identity.event.IdentityAddedEvent;
import org.zerionproject.core.api.identity.event.IdentityRemovedEvent;
import org.zerionproject.core.api.lifecycle.ShutdownManager;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.event.SettingsUpdatedEvent;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.MessageStatus;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.event.GroupAddedEvent;
import org.zerionproject.core.api.sync.event.GroupRemovedEvent;
import org.zerionproject.core.api.sync.event.GroupVisibilityUpdatedEvent;
import org.zerionproject.core.api.sync.event.MessageAddedEvent;
import org.zerionproject.core.api.sync.event.MessageRequestedEvent;
import org.zerionproject.core.api.sync.event.MessageSharedEvent;
import org.zerionproject.core.api.sync.event.MessageStateChangedEvent;
import org.zerionproject.core.api.sync.event.MessageToAckEvent;
import org.zerionproject.core.api.sync.event.MessageToRequestEvent;
import org.zerionproject.core.api.sync.event.MessagesAckedEvent;
import org.zerionproject.core.api.sync.event.MessagesSentEvent;
import org.zerionproject.core.api.transport.IncomingKeys;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.transport.OutgoingKeys;
import org.zerionproject.core.api.transport.TransportKeySet;
import org.zerionproject.core.api.transport.TransportKeys;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.CaptureArgumentAction;
import org.jmock.Expectations;
import org.jmock.Sequence;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.zerionproject.core.api.db.DatabaseComponent.TIMER_NOT_STARTED;
import static org.zerionproject.core.api.record.Record.RECORD_HEADER_BYTES;
import static org.zerionproject.core.api.sync.Group.Visibility.INVISIBLE;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.api.sync.Group.Visibility.VISIBLE;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_LENGTH;
import static org.zerionproject.core.api.sync.validation.MessageState.DELIVERED;
import static org.zerionproject.core.api.sync.validation.MessageState.UNKNOWN;
import static org.zerionproject.core.api.transport.TransportConstants.REORDERING_WINDOW_SIZE;
import static org.zerionproject.core.db.DatabaseConstants.MAX_OFFERED_MESSAGES;
import static org.zerionproject.core.test.TestUtils.getAgreementPrivateKey;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DatabaseComponentImplTest extends BrambleMockTestCase {

	private static final int BATCH_CAPACITY =
			(RECORD_HEADER_BYTES + MAX_MESSAGE_LENGTH) * 2;

	@SuppressWarnings("unchecked")
	private final Database<Object> database = context.mock(Database.class);
	private final ShutdownManager shutdownManager =
			context.mock(ShutdownManager.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final Executor eventExecutor = context.mock(Executor.class);

	private final SecretKey key = getSecretKey();
	private final Object txn = new Object();
	private final ClientId clientId;
	private final int majorVersion;
	private final GroupId groupId;
	private final Group group;
	private final Author author;
	private final Identity identity;
	private final LocalAuthor localAuthor;
	private final String alias;
	private final Message message, message1;
	private final MessageId messageId, messageId1;
	private final Metadata metadata;
	private final TransportId transportId;
	private final long maxLatency;
	private final ContactId contactId;
	private final Contact contact;
	private final KeySetId keySetId;
	private final PendingContactId pendingContactId;
	private final Random random = new Random();
	private final boolean shared = random.nextBoolean();
	private final boolean temporary = random.nextBoolean();

	public DatabaseComponentImplTest() {
		clientId = getClientId();
		majorVersion = 123;
		group = getGroup(clientId, majorVersion);
		groupId = group.getId();
		author = getAuthor();
		identity = getIdentity();
		localAuthor = identity.getLocalAuthor();
		message = getMessage(groupId);
		message1 = getMessage(groupId);
		messageId = message.getId();
		messageId1 = message1.getId();
		metadata = new Metadata();
		metadata.put("foo", new byte[] {'b', 'a', 'r'});
		transportId = getTransportId();
		maxLatency = Integer.MAX_VALUE;
		contact = getContact(author, localAuthor.getId(), true);
		contactId = contact.getId();
		alias = contact.getAlias();
		keySetId = new KeySetId(345);
		pendingContactId = new PendingContactId(getRandomId());
	}

	private DatabaseComponent createDatabaseComponent(Database<Object> database,
			EventBus eventBus, Executor eventExecutor,
			ShutdownManager shutdownManager) {
		return new DatabaseComponentImpl<>(database, Object.class, eventBus,
				eventExecutor, shutdownManager);
	}

	@Test
	public void testSimpleCalls() throws Exception {
		int shutdownHandle = 12345;
		context.checking(new Expectations() {{

			oneOf(database).open(key, null);
			will(returnValue(false));
			oneOf(shutdownManager).addShutdownHook(with(any(Runnable.class)));
			will(returnValue(shutdownHandle));

			oneOf(database).startTransaction();
			will(returnValue(txn));

			oneOf(database).containsIdentity(txn, localAuthor.getId());
			will(returnValue(false));
			oneOf(database).addIdentity(txn, identity);
			oneOf(eventBus).broadcast(with(any(IdentityAddedEvent.class)));

			oneOf(database).containsIdentity(txn, localAuthor.getId());
			will(returnValue(true));
			oneOf(database).containsIdentity(txn, author.getId());
			will(returnValue(false));
			oneOf(database).containsContact(txn, author.getId(),
					localAuthor.getId());
			will(returnValue(false));
			oneOf(database).addContact(txn, author, localAuthor.getId(),
					null, true, false, false, (byte[]) null);
			will(returnValue(contactId));
			oneOf(eventBus).broadcast(with(any(ContactAddedEvent.class)));

			oneOf(database).getContacts(txn);
			will(returnValue(singletonList(contact)));

			oneOf(database).containsGroup(txn, groupId);
			will(returnValue(false));
			oneOf(database).addGroup(txn, group);
			oneOf(eventBus).broadcast(with(any(GroupAddedEvent.class)));

			oneOf(database).containsGroup(txn, groupId);
			will(returnValue(true));

			oneOf(database).getGroups(txn, clientId, majorVersion);
			will(returnValue(singletonList(group)));

			oneOf(database).containsGroup(txn, groupId);
			will(returnValue(true));
			oneOf(database).getGroupVisibility(txn, groupId);
			will(returnValue(emptyMap()));
			oneOf(database).removeGroup(txn, groupId);
			oneOf(eventBus).broadcast(with(any(GroupRemovedEvent.class)));
			oneOf(eventBus).broadcast(with(any(
					GroupVisibilityUpdatedEvent.class)));

			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).removeContact(txn, contactId);
			oneOf(eventBus).broadcast(with(any(ContactRemovedEvent.class)));

			oneOf(database).containsIdentity(txn, localAuthor.getId());
			will(returnValue(true));
			oneOf(database).removeIdentity(txn, localAuthor.getId());
			oneOf(eventBus).broadcast(with(any(IdentityRemovedEvent.class)));

			oneOf(database).commitTransaction(txn);

			oneOf(database).close();
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		assertFalse(db.open(key, null));
		db.transaction(false, transaction -> {
			db.addIdentity(transaction, identity);
			assertEquals(contactId, db.addContact(transaction, author,
					localAuthor.getId(), null, true));
			assertEquals(singletonList(contact),
					db.getContacts(transaction));
			db.addGroup(transaction, group);
			db.addGroup(transaction, group);
			assertEquals(singletonList(group),
					db.getGroups(transaction, clientId, majorVersion));
			db.removeGroup(transaction, group);
			db.removeContact(transaction, contactId);
			db.removeIdentity(transaction, localAuthor.getId());
		});
		db.close();
	}

	@Test(expected = NoSuchGroupException.class)
	public void testLocalMessagesAreNotStoredUnlessGroupExists()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsGroup(txn, groupId);
			will(returnValue(false));
			oneOf(database).abortTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				db.addLocalMessage(transaction, message, metadata, shared,
						temporary));
	}

	@Test
	public void testAddLocalMessage() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsGroup(txn, groupId);
			will(returnValue(true));
			oneOf(database).containsMessage(txn, messageId);
			will(returnValue(false));
			oneOf(database).addMessage(txn, message, DELIVERED, shared,
					temporary, null);
			oneOf(database).mergeMessageMetadata(txn, messageId, metadata);
			oneOf(database).commitTransaction(txn);

			oneOf(eventBus).broadcast(with(any(MessageAddedEvent.class)));
			oneOf(eventBus).broadcast(with(any(
					MessageStateChangedEvent.class)));

			if (shared) {
				oneOf(database).getGroupVisibility(txn, groupId);
				will(returnValue(singletonMap(contactId, true)));
				oneOf(eventBus).broadcast(with(any(MessageSharedEvent.class)));
			}
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				db.addLocalMessage(transaction, message, metadata, shared,
						temporary));
	}

	@Test
	public void testVariousMethodsThrowExceptionIfContactIsMissing()
			throws Exception {
		context.checking(new Expectations() {{

			exactly(27).of(database).startTransaction();
			will(returnValue(txn));
			exactly(27).of(database).containsContact(txn, contactId);
			will(returnValue(false));
			exactly(27).of(database).abortTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		try {
			db.transaction(false, transaction ->
					db.addTransportKeys(transaction, contactId,
							createTransportKeys()));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.containsAcksToSend(transaction, contactId));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.containsMessagesToSend(transaction, contactId,
							123, true));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.generateAck(transaction, contactId, 123));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.generateBatch(transaction, contactId, 123, 456));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.generateOffer(transaction, contactId, 123, 456));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.generateRequest(transaction, contactId, 123));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getContact(transaction, contactId));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.getMessageToSend(transaction, contactId, messageId, 123,
							true));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessagesToAck(transaction, contactId));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessagesToSend(transaction, contactId, 123, 456));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getUnackedMessagesToSend(transaction, contactId));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getUnackedMessageBytesToSend(transaction, contactId));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageStatus(transaction, contactId, groupId));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageStatus(transaction, contactId, messageId));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getGroupVisibility(transaction, contactId, groupId));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getSyncVersions(transaction, contactId));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			Ack a = new Ack(singletonList(messageId));
			db.transaction(false, transaction ->
					db.receiveAck(transaction, contactId, a));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.receiveMessage(transaction, contactId, message));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			Offer o = new Offer(singletonList(messageId));
			db.transaction(false, transaction ->
					db.receiveOffer(transaction, contactId, o));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			Request r = new Request(singletonList(messageId));
			db.transaction(false, transaction ->
					db.receiveRequest(transaction, contactId, r));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.removeContact(transaction, contactId));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setAckSent(transaction, contactId,
							singletonList(messageId)));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setContactAlias(transaction, contactId, alias));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setGroupVisibility(transaction, contactId, groupId,
							SHARED));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setMessagesSent(transaction, contactId,
							singletonList(messageId), 123));
			fail();
		} catch (NoSuchContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setSyncVersions(transaction, contactId, emptyList()));
			fail();
		} catch (NoSuchContactException expected) {

		}
	}

	@Test
	public void testVariousMethodsThrowExceptionIfIdentityIsMissing()
			throws Exception {
		context.checking(new Expectations() {{

			exactly(4).of(database).startTransaction();
			will(returnValue(txn));
			exactly(4).of(database).containsIdentity(txn, localAuthor.getId());
			will(returnValue(false));
			exactly(4).of(database).abortTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		try {
			db.transaction(false, transaction ->
					db.addContact(transaction, author, localAuthor.getId(),
							null, true));
			fail();
		} catch (NoSuchIdentityException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.getIdentity(transaction, localAuthor.getId()));
			fail();
		} catch (NoSuchIdentityException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.removeIdentity(transaction, localAuthor.getId()));
			fail();
		} catch (NoSuchIdentityException expected) {

		}

		try {
			PublicKey publicKey = getAgreementPublicKey();
			PrivateKey privateKey = getAgreementPrivateKey();
			db.transaction(false, transaction ->
					db.setHandshakeKeyPair(transaction, localAuthor.getId(),
							publicKey, privateKey));
			fail();
		} catch (NoSuchIdentityException expected) {

		}
	}

	@Test
	public void testVariousMethodsThrowExceptionIfGroupIsMissing()
			throws Exception {
		context.checking(new Expectations() {{

			exactly(10).of(database).startTransaction();
			will(returnValue(txn));
			exactly(10).of(database).containsGroup(txn, groupId);
			will(returnValue(false));
			exactly(10).of(database).abortTransaction(txn);

			allowing(database).containsContact(txn, contactId);
			will(returnValue(true));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		try {
			db.transaction(true, transaction ->
					db.getGroup(transaction, groupId));
			fail();
		} catch (NoSuchGroupException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getGroupMetadata(transaction, groupId));
			fail();
		} catch (NoSuchGroupException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageIds(transaction, groupId));
			fail();
		} catch (NoSuchGroupException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageIds(transaction, groupId, new Metadata()));
			fail();
		} catch (NoSuchGroupException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageMetadata(transaction, groupId));
			fail();
		} catch (NoSuchGroupException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageMetadata(transaction, groupId,
							new Metadata()));
			fail();
		} catch (NoSuchGroupException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageStatus(transaction, contactId, groupId));
			fail();
		} catch (NoSuchGroupException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.mergeGroupMetadata(transaction, groupId, metadata));
			fail();
		} catch (NoSuchGroupException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.removeGroup(transaction, group));
			fail();
		} catch (NoSuchGroupException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setGroupVisibility(transaction, contactId, groupId,
							SHARED));
			fail();
		} catch (NoSuchGroupException expected) {

		}
	}

	@Test
	public void testVariousMethodsThrowExceptionIfMessageIsMissing()
			throws Exception {
		context.checking(new Expectations() {{

			exactly(16).of(database).startTransaction();
			will(returnValue(txn));
			exactly(16).of(database).containsMessage(txn, messageId);
			will(returnValue(false));
			exactly(16).of(database).abortTransaction(txn);

			allowing(database).containsContact(txn, contactId);
			will(returnValue(true));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		try {
			db.transaction(false, transaction ->
					db.deleteMessage(transaction, messageId));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.deleteMessageMetadata(transaction, messageId));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getGroupId(transaction, messageId));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessage(transaction, messageId));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageMetadata(transaction, messageId));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageState(transaction, messageId));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageStatus(transaction, contactId, messageId));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.mergeMessageMetadata(transaction, messageId, metadata));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setCleanupTimerDuration(transaction, message.getId(),
							HOURS.toMillis(1)));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setMessagePermanent(transaction, message.getId()));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setMessageShared(transaction, message.getId()));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setMessageState(transaction, messageId, DELIVERED));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageDependencies(transaction, messageId));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(true, transaction ->
					db.getMessageDependents(transaction, messageId));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.startCleanupTimer(transaction, messageId));
			fail();
		} catch (NoSuchMessageException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.stopCleanupTimer(transaction, messageId));
			fail();
		} catch (NoSuchMessageException expected) {

		}
	}

	@Test
	public void testVariousMethodsThrowExceptionIfTransportIsMissing()
			throws Exception {
		context.checking(new Expectations() {{

			exactly(8).of(database).startTransaction();
			will(returnValue(txn));
			exactly(8).of(database).containsTransport(txn, transportId);
			will(returnValue(false));
			exactly(8).of(database).abortTransaction(txn);

			allowing(database).containsContact(txn, contactId);
			will(returnValue(true));
			allowing(database).containsPendingContact(txn, pendingContactId);
			will(returnValue(true));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		try {
			db.transaction(false, transaction ->
					db.addTransportKeys(transaction, contactId,
							createHandshakeKeys()));
			fail();
		} catch (NoSuchTransportException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.addTransportKeys(transaction, pendingContactId,
							createHandshakeKeys()));
			fail();
		} catch (NoSuchTransportException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.getTransportKeys(transaction, transportId));
			fail();
		} catch (NoSuchTransportException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.incrementStreamCounter(transaction, transportId,
							keySetId));
			fail();
		} catch (NoSuchTransportException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.removeTransportKeys(transaction, transportId, keySetId));
			fail();
		} catch (NoSuchTransportException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.removeTransport(transaction, transportId));
			fail();
		} catch (NoSuchTransportException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setReorderingWindow(transaction, keySetId, transportId,
							0, 0, new byte[REORDERING_WINDOW_SIZE / 8]));
			fail();
		} catch (NoSuchTransportException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.setTransportKeysActive(transaction, transportId,
							keySetId));
			fail();
		} catch (NoSuchTransportException expected) {

		}
	}

	@Test
	public void testVariousMethodsThrowExceptionIfPendingContactIsMissing()
			throws Exception {
		context.checking(new Expectations() {{

			exactly(3).of(database).startTransaction();
			will(returnValue(txn));
			exactly(3).of(database).containsPendingContact(txn,
					pendingContactId);
			will(returnValue(false));
			exactly(3).of(database).abortTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		try {
			db.transaction(false, transaction ->
					db.addTransportKeys(transaction, pendingContactId,
							createHandshakeKeys()));
			fail();
		} catch (NoSuchPendingContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.getPendingContact(transaction, pendingContactId));
			fail();
		} catch (NoSuchPendingContactException expected) {

		}

		try {
			db.transaction(false, transaction ->
					db.removePendingContact(transaction, pendingContactId));
			fail();
		} catch (NoSuchPendingContactException expected) {

		}
	}

	@Test
	public void testGenerateAck() throws Exception {
		Collection<MessageId> messagesToAck = asList(messageId, messageId1);
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).getMessagesToAck(txn, contactId, 123);
			will(returnValue(messagesToAck));
			oneOf(database).lowerAckFlag(txn, contactId, messagesToAck);
			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction -> {
			Ack a = db.generateAck(transaction, contactId, 123);
			assertNotNull(a);
			assertEquals(messagesToAck, a.getMessageIds());
		});
	}

	@Test
	public void testGenerateBatch() throws Exception {
		Collection<MessageId> ids = asList(messageId, messageId1);
		Collection<Message> messages = asList(message, message1);
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).getMessagesToSend(txn, contactId,
					BATCH_CAPACITY, maxLatency);
			will(returnValue(ids));

			oneOf(database).getMessage(txn, messageId);
			will(returnValue(message));
			oneOf(database).updateRetransmissionData(txn, contactId, messageId,
					maxLatency);

			oneOf(database).getMessage(txn, messageId1);
			will(returnValue(message1));
			oneOf(database).updateRetransmissionData(txn, contactId, messageId1,
					maxLatency);
			oneOf(database).lowerRequestedFlag(txn, contactId, ids);
			oneOf(database).commitTransaction(txn);
			oneOf(eventBus).broadcast(with(any(MessagesSentEvent.class)));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				assertEquals(messages, db.generateBatch(transaction, contactId,
						BATCH_CAPACITY, maxLatency)));
	}

	@Test
	public void testGenerateOffer() throws Exception {
		MessageId messageId1 = new MessageId(getRandomId());
		Collection<MessageId> ids = asList(messageId, messageId1);
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).getMessagesToOffer(txn, contactId, 123, maxLatency);
			will(returnValue(ids));
			oneOf(database).updateRetransmissionData(txn, contactId, messageId,
					maxLatency);
			oneOf(database).updateRetransmissionData(txn, contactId, messageId1,
					maxLatency);
			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction -> {
			Offer o = db.generateOffer(transaction, contactId, 123, maxLatency);
			assertNotNull(o);
			assertEquals(ids, o.getMessageIds());
		});
	}

	@Test
	public void testGenerateRequest() throws Exception {
		MessageId messageId1 = new MessageId(getRandomId());
		Collection<MessageId> ids = asList(messageId, messageId1);
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).getMessagesToRequest(txn, contactId, 123);
			will(returnValue(ids));
			oneOf(database).removeOfferedMessages(txn, contactId, ids);
			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction -> {
			Request r = db.generateRequest(transaction, contactId, 123);
			assertNotNull(r);
			assertEquals(ids, r.getMessageIds());
		});
	}

	@Test
	public void testGenerateRequestedBatch() throws Exception {
		Collection<MessageId> ids = asList(messageId, messageId1);
		Collection<Message> messages = asList(message, message1);
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).getRequestedMessagesToSend(txn, contactId,
					BATCH_CAPACITY, maxLatency);
			will(returnValue(ids));

			oneOf(database).getMessage(txn, messageId);
			will(returnValue(message));
			oneOf(database).updateRetransmissionData(txn, contactId,
					messageId, maxLatency);

			oneOf(database).getMessage(txn, messageId1);
			will(returnValue(message1));
			oneOf(database).updateRetransmissionData(txn, contactId,
					messageId1, maxLatency);
			oneOf(database).lowerRequestedFlag(txn, contactId, ids);
			oneOf(database).commitTransaction(txn);
			oneOf(eventBus).broadcast(with(any(MessagesSentEvent.class)));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				assertEquals(messages, db.generateRequestedBatch(transaction,
						contactId, BATCH_CAPACITY, maxLatency)));
	}

	@Test
	public void testGetMessageToSendMessageNotVisible() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsVisibleMessage(txn, contactId, messageId);
			will(returnValue(false));
			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				assertNull(db.getMessageToSend(transaction, contactId,
						messageId, maxLatency, false)));
	}

	@Test
	public void testGetMessageToSendMessageNotMarkedAsSent() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsVisibleMessage(txn, contactId, messageId);
			will(returnValue(true));
			oneOf(database).getMessage(txn, messageId);
			will(returnValue(message));
			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				assertEquals(message, db.getMessageToSend(transaction,
						contactId, messageId, maxLatency, false)));
	}

	@Test
	public void testGetMessageToSendMessageMarkedAsSent() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsVisibleMessage(txn, contactId, messageId);
			will(returnValue(true));
			oneOf(database).getMessage(txn, messageId);
			will(returnValue(message));
			oneOf(database).updateRetransmissionData(txn, contactId, messageId,
					maxLatency);
			oneOf(database).lowerRequestedFlag(txn, contactId,
					singletonList(messageId));
			oneOf(database).commitTransaction(txn);
			oneOf(eventBus).broadcast(with(any(MessagesSentEvent.class)));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				assertEquals(message, db.getMessageToSend(transaction,
						contactId, messageId, maxLatency, true)));
	}

	@Test
	public void testReceiveAck() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsVisibleMessage(txn, contactId, messageId);
			will(returnValue(true));
			oneOf(database).raiseSeenFlag(txn, contactId, messageId);
			will(returnValue(true));
			oneOf(database).startCleanupTimer(txn, messageId);
			will(returnValue(TIMER_NOT_STARTED));
			oneOf(database).commitTransaction(txn);
			oneOf(eventBus).broadcast(with(any(MessagesAckedEvent.class)));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction -> {
			Ack a = new Ack(singletonList(messageId));
			db.receiveAck(transaction, contactId, a);
		});
	}

	@Test
	public void testReceiveDuplicateAck() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsVisibleMessage(txn, contactId, messageId);
			will(returnValue(true));
			oneOf(database).raiseSeenFlag(txn, contactId, messageId);
			will(returnValue(false));
			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction -> {
			Ack a = new Ack(singletonList(messageId));
			db.receiveAck(transaction, contactId, a);
		});
	}

	@Test
	public void testReceiveAckWithCleanupTimer() throws Exception {
		long deadline = System.currentTimeMillis();
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsVisibleMessage(txn, contactId, messageId);
			will(returnValue(true));
			oneOf(database).raiseSeenFlag(txn, contactId, messageId);
			will(returnValue(true));
			oneOf(database).startCleanupTimer(txn, messageId);
			will(returnValue(deadline));
			oneOf(database).commitTransaction(txn);
			oneOf(eventBus).broadcast(with(any(
					CleanupTimerStartedEvent.class)));
			oneOf(eventBus).broadcast(with(any(MessagesAckedEvent.class)));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction -> {
			Ack a = new Ack(singletonList(messageId));
			db.receiveAck(transaction, contactId, a);
		});
	}

	@Test
	public void testReceiveMessage() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));

			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).getGroupVisibility(txn, contactId, groupId);
			will(returnValue(VISIBLE));
			oneOf(database).containsMessage(txn, messageId);
			will(returnValue(false));
			oneOf(database).addMessage(txn, message, UNKNOWN, false, false,
					contactId);

			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).getGroupVisibility(txn, contactId, groupId);
			will(returnValue(VISIBLE));
			oneOf(database).containsMessage(txn, messageId);
			will(returnValue(true));
			oneOf(database).raiseSeenFlag(txn, contactId, messageId);
			oneOf(database).raiseAckFlag(txn, contactId, messageId);
			oneOf(database).commitTransaction(txn);

			oneOf(eventBus).broadcast(with(any(MessageToAckEvent.class)));
			oneOf(eventBus).broadcast(with(any(MessageAddedEvent.class)));

			oneOf(eventBus).broadcast(with(any(MessageToAckEvent.class)));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction -> {

			db.receiveMessage(transaction, contactId, message);
			db.receiveMessage(transaction, contactId, message);
		});
	}

	@Test
	public void testReceiveDuplicateMessage() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsMessage(txn, messageId);
			will(returnValue(true));
			oneOf(database).getGroupVisibility(txn, contactId, groupId);
			will(returnValue(VISIBLE));

			oneOf(database).raiseSeenFlag(txn, contactId, messageId);
			oneOf(database).raiseAckFlag(txn, contactId, messageId);
			oneOf(database).commitTransaction(txn);

			oneOf(eventBus).broadcast(with(any(MessageToAckEvent.class)));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				db.receiveMessage(transaction, contactId, message));
	}

	@Test
	public void testReceiveMessageWithoutVisibleGroup() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).getGroupVisibility(txn, contactId, groupId);
			will(returnValue(INVISIBLE));
			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				db.receiveMessage(transaction, contactId, message));
	}

	@Test
	public void testReceiveOffer() throws Exception {
		MessageId messageId1 = new MessageId(getRandomId());
		MessageId messageId2 = new MessageId(getRandomId());
		MessageId messageId3 = new MessageId(getRandomId());
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));

			oneOf(database).countOfferedMessages(txn, contactId);
			will(returnValue(MAX_OFFERED_MESSAGES - 2));

			oneOf(database).containsVisibleMessage(txn, contactId, messageId);
			will(returnValue(false));
			oneOf(database).addOfferedMessage(txn, contactId, messageId);

			oneOf(database).containsVisibleMessage(txn, contactId, messageId1);
			will(returnValue(true));
			oneOf(database).raiseSeenFlag(txn, contactId, messageId1);
			oneOf(database).raiseAckFlag(txn, contactId, messageId1);

			oneOf(database).containsVisibleMessage(txn, contactId, messageId2);
			will(returnValue(false));
			oneOf(database).addOfferedMessage(txn, contactId, messageId2);

			oneOf(database).containsVisibleMessage(txn, contactId, messageId3);
			will(returnValue(false));
			oneOf(database).commitTransaction(txn);
			oneOf(eventBus).broadcast(with(any(MessageToAckEvent.class)));
			oneOf(eventBus).broadcast(with(any(MessageToRequestEvent.class)));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		Offer o = new Offer(asList(messageId, messageId1,
				messageId2, messageId3));
		db.transaction(false, transaction ->
				db.receiveOffer(transaction, contactId, o));
	}

	@Test
	public void testReceiveRequest() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsVisibleMessage(txn, contactId, messageId);
			will(returnValue(true));
			oneOf(database).raiseRequestedFlag(txn, contactId, messageId);
			oneOf(database).resetExpiryTime(txn, contactId, messageId);
			oneOf(database).commitTransaction(txn);
			oneOf(eventBus).broadcast(with(any(MessageRequestedEvent.class)));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		Request r = new Request(singletonList(messageId));
		db.transaction(false, transaction ->
				db.receiveRequest(transaction, contactId, r));
	}

	@Test
	public void testSetAckSent() throws Exception {
		Collection<MessageId> acked = asList(messageId, messageId1);
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).lowerAckFlag(txn, contactId, acked);
			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				db.setAckSent(transaction, contactId, acked));
	}

	@Test
	public void testSetMessagesSent() throws Exception {
		long maxLatency = 123456;
		Collection<MessageId> sent = asList(messageId, messageId1);
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));

			oneOf(database).containsVisibleMessage(txn, contactId, messageId);
			will(returnValue(true));
			oneOf(database).getMessageLength(txn, messageId);
			will(returnValue(message.getRawLength()));
			oneOf(database).updateRetransmissionData(txn, contactId, messageId,
					maxLatency);

			oneOf(database).containsVisibleMessage(txn, contactId, messageId1);
			will(returnValue(false));
			oneOf(database).lowerRequestedFlag(txn, contactId,
					singletonList(messageId));
			oneOf(database).commitTransaction(txn);
			oneOf(eventBus).broadcast(with(any(MessagesSentEvent.class)));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				db.setMessagesSent(transaction, contactId, sent, maxLatency));
	}

	@Test
	public void testChangingVisibilityFromInvisibleToVisibleCallsListeners()
			throws Exception {
		AtomicReference<GroupVisibilityUpdatedEvent> event =
				new AtomicReference<>();

		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsGroup(txn, groupId);
			will(returnValue(true));
			oneOf(database).getGroupVisibility(txn, contactId, groupId);
			will(returnValue(INVISIBLE));
			oneOf(database).addGroupVisibility(txn, contactId, groupId, false);
			oneOf(database).commitTransaction(txn);
			oneOf(eventBus).broadcast(with(any(
					GroupVisibilityUpdatedEvent.class)));
			will(new CaptureArgumentAction<>(event,
					GroupVisibilityUpdatedEvent.class, 0));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				db.setGroupVisibility(transaction, contactId, groupId,
						VISIBLE));

		GroupVisibilityUpdatedEvent e = event.get();
		assertNotNull(e);
		assertEquals(singletonList(contactId), e.getAffectedContacts());
	}

	@Test
	public void testChangingVisibilityFromVisibleToInvisibleCallsListeners()
			throws Exception {
		AtomicReference<GroupVisibilityUpdatedEvent> event =
				new AtomicReference<>();

		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsGroup(txn, groupId);
			will(returnValue(true));
			oneOf(database).getGroupVisibility(txn, contactId, groupId);
			will(returnValue(VISIBLE));
			oneOf(database).removeGroupVisibility(txn, contactId, groupId);
			oneOf(database).commitTransaction(txn);
			oneOf(eventBus).broadcast(with(any(
					GroupVisibilityUpdatedEvent.class)));
			will(new CaptureArgumentAction<>(event,
					GroupVisibilityUpdatedEvent.class, 0));
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				db.setGroupVisibility(transaction, contactId, groupId,
						INVISIBLE));

		GroupVisibilityUpdatedEvent e = event.get();
		assertNotNull(e);
		assertEquals(singletonList(contactId), e.getAffectedContacts());
	}

	@Test
	public void testNotChangingVisibilityDoesNotCallListeners()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsGroup(txn, groupId);
			will(returnValue(true));
			oneOf(database).getGroupVisibility(txn, contactId, groupId);
			will(returnValue(VISIBLE));
			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction ->
				db.setGroupVisibility(transaction, contactId, groupId,
						VISIBLE));
	}

	@Test
	public void testTransportKeys() throws Exception {
		TransportKeys transportKeys = createTransportKeys();
		TransportKeySet ks =
				new TransportKeySet(keySetId, contactId, null, transportKeys);
		Collection<TransportKeySet> keys = singletonList(ks);

		context.checking(new Expectations() {{

			oneOf(database).startTransaction();
			will(returnValue(txn));

			oneOf(database).containsTransport(txn, transportId);
			will(returnValue(true));
			oneOf(database).updateTransportKeys(txn, ks);

			oneOf(database).containsTransport(txn, transportId);
			will(returnValue(true));
			oneOf(database).getTransportKeys(txn, transportId);
			will(returnValue(keys));

			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction -> {
			db.updateTransportKeys(transaction, keys);
			assertEquals(keys, db.getTransportKeys(transaction, transportId));
		});
	}

	@Test
	public void testGetMessageStatusByGroupId() throws Exception {
		MessageStatus status =
				new MessageStatus(messageId, contactId, true, true);

		context.checking(new Expectations() {{

			oneOf(database).startTransaction();
			will(returnValue(txn));

			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsGroup(txn, groupId);
			will(returnValue(true));
			oneOf(database).getGroupVisibility(txn, contactId, groupId);
			will(returnValue(VISIBLE));
			oneOf(database).getMessageStatus(txn, contactId, groupId);
			will(returnValue(singletonList(status)));

			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsGroup(txn, groupId);
			will(returnValue(true));
			oneOf(database).getGroupVisibility(txn, contactId, groupId);
			will(returnValue(INVISIBLE));
			oneOf(database).getMessageIds(txn, groupId);
			will(returnValue(singletonList(messageId)));

			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(true, transaction -> {

			Collection<MessageStatus> statuses =
					db.getMessageStatus(transaction, contactId, groupId);
			assertEquals(1, statuses.size());
			MessageStatus s = statuses.iterator().next();
			assertEquals(messageId, s.getMessageId());
			assertEquals(contactId, s.getContactId());
			assertTrue(s.isSent());
			assertTrue(s.isSeen());

			statuses = db.getMessageStatus(transaction, contactId, groupId);
			assertEquals(1, statuses.size());
			s = statuses.iterator().next();
			assertEquals(messageId, s.getMessageId());
			assertEquals(contactId, s.getContactId());
			assertFalse(s.isSent());
			assertFalse(s.isSeen());
		});
	}

	@Test
	public void testGetMessageStatusByMessageId() throws Exception {
		MessageStatus status =
				new MessageStatus(messageId, contactId, true, true);

		context.checking(new Expectations() {{

			oneOf(database).startTransaction();
			will(returnValue(txn));

			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsMessage(txn, messageId);
			will(returnValue(true));
			oneOf(database).getMessageStatus(txn, contactId, messageId);
			will(returnValue(status));

			oneOf(database).containsContact(txn, contactId);
			will(returnValue(true));
			oneOf(database).containsMessage(txn, messageId);
			will(returnValue(true));
			oneOf(database).getMessageStatus(txn, contactId, messageId);
			will(returnValue(null));

			oneOf(database).commitTransaction(txn);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(true, transaction -> {

			MessageStatus s =
					db.getMessageStatus(transaction, contactId, messageId);
			assertEquals(messageId, s.getMessageId());
			assertEquals(contactId, s.getContactId());
			assertTrue(s.isSent());
			assertTrue(s.isSeen());

			s = db.getMessageStatus(transaction, contactId, messageId);
			assertEquals(messageId, s.getMessageId());
			assertEquals(contactId, s.getContactId());
			assertFalse(s.isSent());
			assertFalse(s.isSeen());
		});
	}

	private TransportKeys createHandshakeKeys() {
		SecretKey inPrevTagKey = getSecretKey();
		SecretKey inPrevHeaderKey = getSecretKey();
		IncomingKeys inPrev = new IncomingKeys(inPrevTagKey, inPrevHeaderKey,
				1, 123, new byte[4]);
		SecretKey inCurrTagKey = getSecretKey();
		SecretKey inCurrHeaderKey = getSecretKey();
		IncomingKeys inCurr = new IncomingKeys(inCurrTagKey, inCurrHeaderKey,
				2, 234, new byte[4]);
		SecretKey inNextTagKey = getSecretKey();
		SecretKey inNextHeaderKey = getSecretKey();
		IncomingKeys inNext = new IncomingKeys(inNextTagKey, inNextHeaderKey,
				3, 345, new byte[4]);
		SecretKey outCurrTagKey = getSecretKey();
		SecretKey outCurrHeaderKey = getSecretKey();
		OutgoingKeys outCurr = new OutgoingKeys(outCurrTagKey, outCurrHeaderKey,
				2, 456, true);
		return new TransportKeys(transportId, inPrev, inCurr, inNext, outCurr,
				getSecretKey(), true);
	}

	private TransportKeys createTransportKeys() {
		SecretKey inPrevTagKey = getSecretKey();
		SecretKey inPrevHeaderKey = getSecretKey();
		IncomingKeys inPrev = new IncomingKeys(inPrevTagKey, inPrevHeaderKey,
				1, 123, new byte[4]);
		SecretKey inCurrTagKey = getSecretKey();
		SecretKey inCurrHeaderKey = getSecretKey();
		IncomingKeys inCurr = new IncomingKeys(inCurrTagKey, inCurrHeaderKey,
				2, 234, new byte[4]);
		SecretKey inNextTagKey = getSecretKey();
		SecretKey inNextHeaderKey = getSecretKey();
		IncomingKeys inNext = new IncomingKeys(inNextTagKey, inNextHeaderKey,
				3, 345, new byte[4]);
		SecretKey outCurrTagKey = getSecretKey();
		SecretKey outCurrHeaderKey = getSecretKey();
		OutgoingKeys outCurr = new OutgoingKeys(outCurrTagKey, outCurrHeaderKey,
				2, 456, true);
		return new TransportKeys(transportId, inPrev, inCurr, inNext, outCurr);
	}

	@Test
	public void testMergeSettings() throws Exception {
		Settings before = new Settings();
		before.put("foo", "bar");
		before.put("baz", "bam");
		Settings update = new Settings();
		update.put("baz", "qux");
		Settings merged = new Settings();
		merged.put("foo", "bar");
		merged.put("baz", "qux");
		context.checking(new Expectations() {{

			oneOf(database).startTransaction();
			will(returnValue(txn));

			oneOf(database).getSettings(txn, "namespace");
			will(returnValue(before));
			oneOf(database).mergeSettings(txn, update, "namespace");
			oneOf(eventBus).broadcast(with(any(SettingsUpdatedEvent.class)));

			oneOf(database).getSettings(txn, "namespace");
			will(returnValue(merged));

			oneOf(database).commitTransaction(txn);
		}});

		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction -> {

			db.mergeSettings(transaction, update, "namespace");

			db.mergeSettings(transaction, update, "namespace");
		});
	}

	@Test(expected = IllegalStateException.class)
	public void testCannotStartReadTransactionDuringReadTransaction()
			throws Exception {
		testCannotStartTransactionDuringTransaction(true, true);
	}

	@Test(expected = IllegalStateException.class)
	public void testCannotStartWriteTransactionDuringReadTransaction()
			throws Exception {
		testCannotStartTransactionDuringTransaction(true, false);
	}

	@Test(expected = IllegalStateException.class)
	public void testCannotStartReadTransactionDuringWriteTransaction()
			throws Exception {
		testCannotStartTransactionDuringTransaction(false, true);
	}

	@Test(expected = IllegalStateException.class)
	public void testCannotStartWriteTransactionDuringWriteTransaction()
			throws Exception {
		testCannotStartTransactionDuringTransaction(false, false);
	}

	private void testCannotStartTransactionDuringTransaction(
			boolean firstTxnReadOnly, boolean secondTxnReadOnly)
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
		}});

		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		assertNotNull(db.startTransaction(firstTxnReadOnly));
		db.startTransaction(secondTxnReadOnly);
		fail();
	}

	@Test
	public void testCannotAddLocalIdentityAsContact() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsIdentity(txn, localAuthor.getId());
			will(returnValue(true));

			oneOf(database).containsIdentity(txn, author.getId());
			will(returnValue(true));
			oneOf(database).abortTransaction(txn);
		}});

		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		try {
			db.transaction(false, transaction ->
					db.addContact(transaction, author, localAuthor.getId(),
							null, true));
			fail();
		} catch (ContactExistsException expected) {
			assertEquals(localAuthor.getId(), expected.getLocalAuthorId());
			assertEquals(author, expected.getRemoteAuthor());
		}
	}

	@Test
	public void testCannotAddDuplicateContact() throws Exception {
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			oneOf(database).containsIdentity(txn, localAuthor.getId());
			will(returnValue(true));
			oneOf(database).containsIdentity(txn, author.getId());
			will(returnValue(false));

			oneOf(database).containsContact(txn, author.getId(),
					localAuthor.getId());
			will(returnValue(true));
			oneOf(database).abortTransaction(txn);
		}});

		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		try {
			db.transaction(false, transaction ->
					db.addContact(transaction, author, localAuthor.getId(),
							null, true));
			fail();
		} catch (ContactExistsException expected) {
			assertEquals(localAuthor.getId(), expected.getLocalAuthorId());
			assertEquals(author, expected.getRemoteAuthor());
		}
	}

	@Test
	public void testMessageDependencies() throws Exception {
		int shutdownHandle = 12345;
		MessageId messageId2 = new MessageId(getRandomId());

		context.checking(new Expectations() {{

			oneOf(database).open(key, null);
			will(returnValue(false));
			oneOf(shutdownManager).addShutdownHook(with(any(Runnable.class)));
			will(returnValue(shutdownHandle));

			oneOf(database).startTransaction();
			will(returnValue(txn));

			oneOf(database).containsGroup(txn, groupId);
			will(returnValue(true));
			oneOf(database).containsMessage(txn, messageId);
			will(returnValue(false));
			oneOf(database).addMessage(txn, message, DELIVERED, shared,
					temporary, null);
			oneOf(database).mergeMessageMetadata(txn, messageId, metadata);

			oneOf(database).containsMessage(txn, messageId);
			will(returnValue(true));
			oneOf(database).getMessageState(txn, messageId);
			will(returnValue(DELIVERED));
			oneOf(database).addMessageDependency(txn, message, messageId1,
					DELIVERED);
			oneOf(database).addMessageDependency(txn, message, messageId2,
					DELIVERED);

			oneOf(database).containsMessage(txn, messageId);
			will(returnValue(true));
			oneOf(database).getMessageDependencies(txn, messageId);

			oneOf(database).containsMessage(txn, messageId);
			will(returnValue(true));
			oneOf(database).getMessageDependents(txn, messageId);

			oneOf(eventBus).broadcast(with(any(MessageAddedEvent.class)));
			oneOf(eventBus).broadcast(with(any(
					MessageStateChangedEvent.class)));

			if (shared) {
				oneOf(database).getGroupVisibility(txn, groupId);
				will(returnValue(singletonMap(contactId, true)));
				oneOf(eventBus).broadcast(with(any(MessageSharedEvent.class)));
			}

			oneOf(database).commitTransaction(txn);

			oneOf(database).close();
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		assertFalse(db.open(key, null));
		db.transaction(false, transaction -> {
			db.addLocalMessage(transaction, message, metadata, shared,
					temporary);
			Collection<MessageId> dependencies = new ArrayList<>(2);
			dependencies.add(messageId1);
			dependencies.add(messageId2);
			db.addMessageDependencies(transaction, message, dependencies);
			db.getMessageDependencies(transaction, messageId);
			db.getMessageDependents(transaction, messageId);
		});
		db.close();
	}

	@Test
	public void testCommitActionsOccurInOrder() throws Exception {
		TestEvent action1 = new TestEvent();
		Runnable action2 = () -> {
		};
		TestEvent action3 = new TestEvent();
		Runnable action4 = () -> {
		};

		Sequence sequence = context.sequence("sequence");
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(returnValue(txn));
			inSequence(sequence);
			oneOf(database).commitTransaction(txn);
			inSequence(sequence);
			oneOf(eventBus).broadcast(action1);
			inSequence(sequence);
			oneOf(eventExecutor).execute(action2);
			inSequence(sequence);
			oneOf(eventBus).broadcast(action3);
			inSequence(sequence);
			oneOf(eventExecutor).execute(action4);
			inSequence(sequence);
		}});
		DatabaseComponent db = createDatabaseComponent(database, eventBus,
				eventExecutor, shutdownManager);

		db.transaction(false, transaction -> {
			transaction.attach(action1);
			transaction.attach(action2);
			transaction.attach(action3);
			transaction.attach(action4);
		});
	}

	private static class TestEvent extends Event {
	}
}

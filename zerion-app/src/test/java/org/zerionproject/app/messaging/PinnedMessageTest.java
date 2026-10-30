package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.MessagingManager.PinResult;
import org.zerionproject.app.api.messaging.PinnedMessage;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.event.PinnedMessageChangedEvent;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_PIN;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class PinnedMessageTest extends BrambleTestCase {

	private static final long HOUR = 60L * 60 * 1000;

	private final File testDir = getTestDirectory();
	private final long now = System.currentTimeMillis();

	private Device me;
	private Device contact;

	@NotNullByDefault
	private static class Device {

		private final File dir;
		private final SecretKey key = getSecretKey();
		private final List<PinnedMessageChangedEvent> pinEvents =
				Collections.synchronizedList(new ArrayList<>());
		@Nullable
		private ContactGroupScanTestComponent component;
		private DatabaseComponent db;
		private ClientHelper clientHelper;
		private MessagingManager messagingManager;
		private PrivateMessageValidator validator;
		private ContactId contactId;
		private GroupId group;

		private Device(File dir) {
			this.dir = dir;
		}
	}

	@Before
	public void setUp() throws Exception {
		me = new Device(new File(testDir, "me"));
		contact = new Device(new File(testDir, "contact"));
		assertTrue(me.dir.mkdirs());
		assertTrue(contact.dir.mkdirs());
		Identity mine = build(me).getIdentityManager().createIdentity("Me");
		Identity theirs = build(contact).getIdentityManager()
				.createIdentity("Contact");
		start(me, mine);
		start(contact, theirs);
		addContact(me, theirs.getLocalAuthor(), mine, true);
		addContact(contact, mine.getLocalAuthor(), theirs, false);
		assertEquals(me.group, contact.group);
	}

	@After
	public void tearDown() throws Exception {
		stop(me);
		stop(contact);
		deleteTestDirectory(testDir);
	}

	@Test
	public void pinningNeedsVersion15AndIsOfferedOnlyThen() throws Exception {
		assertEquals(15, MessagingManager.PIN_MIN_VERSION);
		assertEquals(15, MESSAGE_PIN);
		assertTrue(MessagingManager.MINOR_VERSION
				>= MessagingManager.PIN_MIN_VERSION);
		assertTrue(seeing(me, 15).supportsPin(me.contactId));
		assertFalse(seeing(me, 14).supportsPin(me.contactId));
		assertFalse("the iOS client announces 3",
				seeing(me, 3).supportsPin(me.contactId));
	}

	@Test
	public void aPinTravelsToTheContactAndIsStoredOnBothSides()
			throws Exception {
		MessageId text = sendText(me, "pin me");
		transfer(me, contact);

		assertEquals(PinResult.PINNED,
				seeing(me, 15).pinMessage(me.contactId, text));

		PinnedMessage mine = me.messagingManager.getPinnedMessage(me.contactId);
		assertNotNull(mine);
		assertEquals(text, mine.getMessageId());
		assertTrue(mine.isLocal());
		assertTrue(mine.getTimestamp() >= now);
		Collection<MessageId> records = localControls(me, MESSAGE_PIN);
		assertEquals(1, records.size());
		BdfList record = body(me, records.iterator().next());
		assertEquals(2, record.size());
		assertEquals(MESSAGE_PIN, (long) record.getLong(0));
		assertArrayEquals(text.getBytes(), record.getRaw(1));
		assertEquals(1, events(me).size());
		assertEquals(text, events(me).get(0).getMessageId());
		assertTrue(events(me).get(0).isLocal());

		transfer(me, contact);

		PinnedMessage theirs = contact.messagingManager.getPinnedMessage(
				contact.contactId);
		assertNotNull(theirs);
		assertEquals(text, theirs.getMessageId());
		assertFalse(theirs.isLocal());
		assertEquals(1, events(contact).size());
		assertEquals(text, events(contact).get(0).getMessageId());
		assertFalse(events(contact).get(0).isLocal());
		assertTrue("the record is not kept on the receiving side",
				remoteControls(contact, MESSAGE_PIN).isEmpty());
	}

	@Test
	public void pinningAnotherMessageReplacesTheFirst() throws Exception {
		MessageId first = sendText(me, "first");
		MessageId second = sendText(me, "second");
		transfer(me, contact);
		MessagingManagerImpl seen = seeing(me, 15);

		assertEquals(PinResult.PINNED, seen.pinMessage(me.contactId, first));
		assertEquals(PinResult.PINNED, seen.pinMessage(me.contactId, second));

		assertEquals(second, pinnedId(me));
		assertEquals("the earlier record is dropped", 1,
				localControls(me, MESSAGE_PIN).size());
		transfer(me, contact);
		assertEquals(second, pinnedId(contact));
	}

	@Test
	public void eitherSideCanUnpinWithARecordThatNamesNoMessage()
			throws Exception {
		MessageId text = sendText(me, "hello");
		transfer(me, contact);
		seeing(me, 15).pinMessage(me.contactId, text);
		transfer(me, contact);
		assertEquals(text, pinnedId(contact));

		assertTrue(seeing(contact, 15).unpinMessage(contact.contactId));

		assertNull(pinnedId(contact));
		Collection<MessageId> records = localControls(contact, MESSAGE_PIN);
		assertEquals(1, records.size());
		BdfList record = body(contact, records.iterator().next());
		assertEquals(2, record.size());
		assertEquals(MESSAGE_PIN, (long) record.getLong(0));
		assertNull(record.getOptionalRaw(1));

		transfer(contact, me);

		assertNull(pinnedId(me));
		PinnedMessageChangedEvent last =
				events(me).get(events(me).size() - 1);
		assertNull(last.getMessageId());
		assertFalse(last.isLocal());
	}

	@Test
	public void theContactMayPinAMessageIDidNotSend() throws Exception {
		MessageId text = sendText(me, "yours to pin");
		transfer(me, contact);

		assertEquals(PinResult.PINNED,
				seeing(contact, 15).pinMessage(contact.contactId, text));
		transfer(contact, me);

		PinnedMessage mine = me.messagingManager.getPinnedMessage(me.contactId);
		assertNotNull(mine);
		assertEquals(text, mine.getMessageId());
		assertFalse(mine.isLocal());
	}

	@Test
	public void aContactBelow15GetsNoRecordAndThePinStaysHere()
			throws Exception {
		MessageId text = sendText(me, "old contact");
		transfer(me, contact);

		assertEquals(PinResult.PINNED_HERE_ONLY,
				seeing(me, 14).pinMessage(me.contactId, text));

		assertEquals(text, pinnedId(me));
		assertTrue(localControls(me, MESSAGE_PIN).isEmpty());
		transfer(me, contact);
		assertNull(pinnedId(contact));

		assertFalse(seeing(me, 14).unpinMessage(me.contactId));
		assertNull(pinnedId(me));
		assertTrue(localControls(me, MESSAGE_PIN).isEmpty());
	}

	@Test
	public void aPinForAnUnknownMessageWaitsForIt() throws Exception {
		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, getRandomId()), now));
		assertNull(pinnedId(contact));
		assertTrue(events(contact).isEmpty());
		assertEquals("the record is kept for the message", 1,
				remoteControls(contact, MESSAGE_PIN).size());
	}

	@Test
	public void aPinThatArrivesBeforeItsMessageIsAppliedWhenItArrives()
			throws Exception {
		MessageId text = sendText(me, "pin me");
		assertEquals(PinResult.PINNED,
				seeing(me, 15).pinMessage(me.contactId, text));
		MessageId record = localControls(me, MESSAGE_PIN).iterator().next();
		Message textMessage = null, pinMessage = null;
		for (Message m : outbox(me)) {
			if (m.getId().equals(text)) textMessage = m;
			else if (m.getId().equals(record)) pinMessage = m;
		}
		assertNotNull(textMessage);
		assertNotNull(pinMessage);

		assertEquals(MessageState.DELIVERED, deliver(contact, pinMessage));
		assertNull("applied before its message arrived", pinnedId(contact));
		assertTrue(events(contact).isEmpty());
		assertEquals(1, remoteControls(contact, MESSAGE_PIN).size());

		assertEquals(MessageState.DELIVERED, deliver(contact, textMessage));
		assertEquals(text, pinnedId(contact));
		assertEquals(1, events(contact).size());
		assertEquals(text, events(contact).get(0).getMessageId());
		assertFalse(events(contact).get(0).isLocal());
		assertTrue("the record goes once it is applied",
				remoteControls(contact, MESSAGE_PIN).isEmpty());
	}

	@Test
	public void anUnpinThatOvertakesAWaitingPinCancelsIt() throws Exception {
		MessageId text = sendText(me, "pin me");
		Message textMessage = null;
		for (Message m : outbox(me)) {
			if (m.getId().equals(text)) textMessage = m;
		}
		assertNotNull(textMessage);
		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, text.getBytes()), now - 1));
		assertEquals(1, remoteControls(contact, MESSAGE_PIN).size());
		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, BdfDictionary.NULL_VALUE), now));
		assertTrue("the older waiting pin is dropped",
				remoteControls(contact, MESSAGE_PIN).isEmpty());
		assertEquals(MessageState.DELIVERED, deliver(contact, textMessage));
		assertNull(pinnedId(contact));
		assertTrue(events(contact).isEmpty());
	}

	@Test
	public void malformedPinRecordsAreInvalid() throws Exception {
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, new byte[31]), now));
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, getRandomId(), 1L), now));
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, "id"), now));
		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, BdfDictionary.NULL_VALUE), now));
	}

	@Test
	public void anUnpinWhenNothingIsPinnedChangesNothing() throws Exception {
		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, BdfDictionary.NULL_VALUE), now));
		assertTrue(events(contact).isEmpty());
		assertTrue(seeing(contact, 15).unpinMessage(contact.contactId));
		assertTrue(localControls(contact, MESSAGE_PIN).isEmpty());
	}

	@Test
	public void viewOnceAndSecretNotesCannotBePinnedByEitherSide()
			throws Exception {
		MessagingManagerImpl seen = seeing(me, 15);
		AttachmentHeader photo = me.messagingManager.addLocalAttachment(
				me.group, now - HOUR, "image/jpeg",
				new ByteArrayInputStream(pictureBytes()));
		PrivateMessage viewOnce = factory(me).createViewOncePrivateMessage(
				me.group, now - HOUR, photo, NO_AUTO_DELETE_TIMER, null);
		me.db.transaction(false, txn -> seen.addLocalMessage(txn, viewOnce));
		MessageId secret = sendText(me, "SECRET:30:hidden");
		transfer(me, contact);

		assertEquals(PinResult.NOT_PINNABLE, seen.pinMessage(me.contactId,
				viewOnce.getMessage().getId()));
		assertEquals(PinResult.NOT_PINNABLE,
				seen.pinMessage(me.contactId, secret));
		assertEquals(PinResult.NOT_PINNABLE,
				seen.pinMessage(me.contactId, new MessageId(getRandomId())));
		assertNull(pinnedId(me));
		assertTrue(localControls(me, MESSAGE_PIN).isEmpty());

		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, secret.getBytes()), now));
		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, viewOnce.getMessage().getId()
						.getBytes()), now));
		assertNull(pinnedId(contact));
		assertTrue(events(contact).isEmpty());
	}

	@Test
	public void deletingThePinnedMessageHereClearsThePin() throws Exception {
		MessageId text = sendText(me, "gone soon");
		MessageId other = sendText(me, "stays");
		transfer(me, contact);
		seeing(me, 15).pinMessage(me.contactId, text);
		transfer(me, contact);
		assertEquals(text, pinnedId(contact));
		events(contact).clear();

		contact.db.transaction(false, txn -> contact.messagingManager
				.deleteMessages(txn, contact.contactId,
						Collections.singleton(other)));
		assertEquals("another message leaves the pin alone", text,
				pinnedId(contact));
		assertTrue(events(contact).isEmpty());

		contact.db.transaction(false, txn -> contact.messagingManager
				.deleteMessages(txn, contact.contactId,
						Collections.singleton(text)));
		assertNull(pinnedId(contact));
		assertEquals(1, events(contact).size());
		assertNull(events(contact).get(0).getMessageId());
		assertEquals("the sender keeps its pin", text, pinnedId(me));
	}

	@Test
	public void deletingThePinnedMessageForEveryoneClearsItOnBothSides()
			throws Exception {
		MessageId text = sendText(me, "withdrawn");
		transfer(me, contact);
		MessagingManagerImpl seen = seeing(me, 15);
		seen.pinMessage(me.contactId, text);
		transfer(me, contact);
		assertEquals(text, pinnedId(contact));

		assertTrue(seen.deleteForEveryone(me.contactId, singletonList(text)));
		assertNull(pinnedId(me));
		transfer(me, contact);

		assertNull(pinnedId(contact));
		assertFalse(stillStored(contact, text));
	}

	@Test
	public void clearingTheChatClearsThePin() throws Exception {
		MessageId text = sendText(me, "cleared");
		seeing(me, 15).pinMessage(me.contactId, text);
		assertEquals(text, pinnedId(me));

		me.db.transaction(false, txn -> me.messagingManager
				.deleteAllMessages(txn, me.contactId));

		assertNull(pinnedId(me));
	}

	@Test
	public void aPinThatWaitsToBeSentGoesWithItsDeletedMessage()
			throws Exception {
		MessageId text = sendText(me, "never sent");
		assertEquals(PinResult.PINNED,
				seeing(me, 15).pinMessage(me.contactId, text));
		assertEquals(1, localControls(me, MESSAGE_PIN).size());

		me.db.transaction(false, txn -> me.messagingManager
				.deleteMessages(txn, me.contactId, Collections.singleton(text)));

		assertNull(pinnedId(me));
		assertTrue(localControls(me, MESSAGE_PIN).isEmpty());
	}

	@Nullable
	@Test
	public void twoSimultaneousPinsConvergeOnBothPhones() throws Exception {
		MessageId a = sendText(me, "a");
		MessageId b = sendText(me, "b");
		transfer(me, contact);

		assertEquals(PinResult.PINNED, seeing(me, 15).pinMessage(me.contactId,
				a));
		assertEquals(PinResult.PINNED,
				seeing(contact, 15).pinMessage(contact.contactId, b));
		MessageId recordA = localControls(me, MESSAGE_PIN).iterator().next();
		MessageId recordB =
				localControls(contact, MESSAGE_PIN).iterator().next();
		long atA = me.messagingManager.getPinnedMessage(me.contactId)
				.getTimestamp();
		long atB = contact.messagingManager.getPinnedMessage(
				contact.contactId).getTimestamp();

		transfer(me, contact);
		transfer(contact, me);

		MessageId expected = MessagingManagerImpl.isNewerPinRecord(atA,
				recordA.getBytes(), atB, recordB) ? b : a;
		assertEquals(expected, pinnedId(me));
		assertEquals(expected, pinnedId(contact));
	}

	@Test
	public void anOlderPinRecordDoesNotReplaceANewerPin() throws Exception {
		MessageId mine = sendText(me, "newer");
		MessageId old = sendText(me, "older");
		transfer(me, contact);
		seeing(contact, 15).pinMessage(contact.contactId, mine);
		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, old.getBytes()), now - HOUR));
		assertEquals(mine, pinnedId(contact));
		assertTrue(remoteControls(contact, MESSAGE_PIN).isEmpty());
	}

	@Test
	public void aLocalPinSortsAfterAWaitingRemotePinItReplaces()
			throws Exception {
		MessageId mine = sendText(contact, "mine");
		transfer(contact, me);
		long ahead = System.currentTimeMillis() + HOUR;
		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, getRandomId()), ahead));
		assertEquals(1, remoteControls(contact, MESSAGE_PIN).size());
		seeing(contact, 15).pinMessage(contact.contactId, mine);
		assertTrue(remoteControls(contact, MESSAGE_PIN).isEmpty());
		MessageId record =
				localControls(contact, MESSAGE_PIN).iterator().next();
		long at = contact.db.transactionWithResult(true, txn ->
				contact.db.getMessage(txn, record).getTimestamp());
		assertTrue("the local record is newer than the waiting one: " + at,
				at > ahead);
	}

	@Test
	public void anOlderPinRecordDoesNotUndoANewerUnpin() throws Exception {
		MessageId text = sendText(me, "hello");
		transfer(me, contact);
		seeing(contact, 15).pinMessage(contact.contactId, text);
		assertTrue(seeing(contact, 15).unpinMessage(contact.contactId));
		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(MESSAGE_PIN, text.getBytes()), now - HOUR));
		assertNull(pinnedId(contact));
	}

	@Test
	public void equalTimesAreOrderedByTheRecordId() {
		byte[] low = new byte[32];
		byte[] high = new byte[32];
		high[0] = (byte) 0x80;
		low[0] = 0x7F;
		assertTrue(MessagingManagerImpl.isNewerPinRecord(5L, low, 5L,
				new MessageId(high)));
		assertFalse(MessagingManagerImpl.isNewerPinRecord(5L, high, 5L,
				new MessageId(low)));
		assertFalse(MessagingManagerImpl.isNewerPinRecord(5L, high, 5L,
				new MessageId(high)));
		assertTrue(MessagingManagerImpl.isNewerPinRecord(5L, high, 6L,
				new MessageId(low)));
		assertFalse(MessagingManagerImpl.isNewerPinRecord(6L, low, 5L,
				new MessageId(high)));
		assertTrue(MessagingManagerImpl.isNewerPinRecord(null, null, 1L,
				new MessageId(low)));
	}

	private MessageId pinnedId(Device d) throws Exception {
		PinnedMessage p = d.messagingManager.getPinnedMessage(d.contactId);
		return p == null ? null : p.getMessageId();
	}

	private MessageId sendText(Device d, String text) throws Exception {
		PrivateMessage pm = factory(d).createPrivateMessage(d.group,
				nextTimestamp(), text, Collections.emptyList());
		d.db.transaction(false, txn ->
				d.messagingManager.addLocalMessage(txn, pm));
		return pm.getMessage().getId();
	}

	private long timestamp = 0;

	private long nextTimestamp() {
		if (timestamp == 0) timestamp = now - HOUR;
		return ++timestamp;
	}

	private static byte[] pictureBytes() {
		byte[] b = new byte[2048];
		b[0] = (byte) 0xFF;
		b[1] = (byte) 0xD8;
		b[2] = (byte) 0xFF;
		return b;
	}

	private PrivateMessageFactory factory(Device d) {
		return requireComponent(d).getPrivateMessageFactory();
	}

	private BdfList body(Device d, MessageId id) throws Exception {
		Message m = d.db.transactionWithResult(true, txn ->
				d.db.getMessage(txn, id));
		return d.clientHelper.toList(m);
	}

	private boolean stillStored(Device d, MessageId id) throws Exception {
		return d.db.transactionWithResult(true, txn -> {
			try {
				d.db.getMessageState(txn, id);
				return true;
			} catch (NoSuchMessageException e) {
				return false;
			}
		});
	}

	private void transfer(Device from, Device to) throws Exception {
		List<Message> batch = outbox(from);
		to.db.transaction(false, txn -> {
			for (Message m : batch) to.db.receiveMessage(txn, to.contactId, m);
		});
		for (Message m : batch) awaitSettled(to, m.getId());
	}

	private void awaitSettled(Device d, MessageId id) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			MessageState s;
			try {
				s = d.db.transactionWithResult(true, txn ->
						d.db.getMessageState(txn, id));
			} catch (NoSuchMessageException removedAfterDelivery) {
				return;
			}
			if (s != MessageState.UNKNOWN && s != MessageState.PENDING) return;
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("the message was never delivered");
			}
			Thread.sleep(20);
		}
	}

	private List<Message> outbox(Device d) throws Exception {
		Collection<Message> batch = d.db.transactionWithNullableResult(false,
				txn -> d.db.generateBatch(txn, d.contactId, 10_000_000L,
						60_000L));
		List<Message> mine = new ArrayList<>();
		if (batch == null) return mine;
		for (Message m : batch) {
			if (m.getGroupId().equals(d.group)) mine.add(m);
		}
		return mine;
	}

	private Collection<MessageId> localControls(Device d, int type)
			throws Exception {
		return controls(d, type, true);
	}

	private Collection<MessageId> remoteControls(Device d, int type)
			throws Exception {
		return controls(d, type, false);
	}

	private Collection<MessageId> controls(Device d, int type, boolean local)
			throws Exception {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry("messageType", type),
				new BdfEntry("local", local));
		return d.db.transactionWithResult(true, txn ->
				d.clientHelper.getMessageIds(txn, d.group, query));
	}

	private MessageState deliverBody(Device d, BdfList body, long ts)
			throws Exception {
		return deliver(d, create(d, body, ts));
	}

	private Message create(Device d, BdfList body, long ts)
			throws FormatException {
		return d.clientHelper.createMessage(d.group, ts,
				d.clientHelper.toByteArray(body));
	}

	private MessageState deliver(Device d, Message m) throws Exception {
		d.db.transaction(false, txn -> d.db.receiveMessage(txn, d.contactId,
				m));
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			MessageState s;
			try {
				s = d.db.transactionWithResult(true, txn ->
						d.db.getMessageState(txn, m.getId()));
			} catch (NoSuchMessageException removedAfterDelivery) {
				return MessageState.DELIVERED;
			}
			if (s != MessageState.UNKNOWN && s != MessageState.PENDING) {
				return s;
			}
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("the message was never validated");
			}
			Thread.sleep(20);
		}
	}

	private MessagingManagerImpl seeing(Device d, int minorVersion) {
		ContactGroupScanTestComponent c = requireComponent(d);
		ClientVersioningManager real = c.getClientVersioningManager();
		ClientVersioningManager seen = (ClientVersioningManager)
				Proxy.newProxyInstance(
						ClientVersioningManager.class.getClassLoader(),
						new Class<?>[] {ClientVersioningManager.class},
						(proxy, method, args) -> {
							if (method.getName().equals(
									"getClientMinorVersion")) {
								return minorVersion;
							}
							return method.invoke(real, args);
						});
		return new MessagingManagerImpl(d.db, d.clientHelper, seen,
				c.getMetadataParser(), c.getConversationManager(),
				c.getMessageTracker(), c.getContactGroupFactory(),
				c.getAutoDeleteManager(), c.getStreamingAttachmentWriter(),
				c.getIdentityManager(), d.validator, c.getCryptoComponent());
	}

	private static ContactGroupScanTestComponent requireComponent(Device d) {
		ContactGroupScanTestComponent c = d.component;
		assertNotNull(c);
		return c;
	}

	private ContactGroupScanTestComponent build(Device d) {
		ContactGroupScanTestComponent c =
				DaggerContactGroupScanTestComponent.builder()
						.testDatabaseConfigModule(
								new TestDatabaseConfigModule(d.dir))
						.build();
		ContactGroupScanTestComponent.Helper.injectEagerSingletons(c);
		d.component = c;
		return c;
	}

	private void start(Device d, @Nullable Identity identity)
			throws Exception {
		ContactGroupScanTestComponent c = requireComponent(d);
		if (identity != null) {
			c.getIdentityManager().registerIdentity(identity);
		}
		LifecycleManager lifecycle = c.getLifecycleManager();
		lifecycle.startServices(d.key);
		lifecycle.waitForStartup();
		d.db = c.getDatabaseComponent();
		d.clientHelper = c.getClientHelper();
		d.messagingManager = c.getMessagingManager();
		d.validator = c.getPrivateMessageValidator();
		c.getEventBus().addListener(new EventListener() {
			@Override
			public void eventOccurred(Event e) {
				if (e instanceof PinnedMessageChangedEvent) {
					d.pinEvents.add((PinnedMessageChangedEvent) e);
				} else if (e instanceof Settled) {
					((Settled) e).latch.countDown();
				}
			}
		});
	}

	private void stop(@Nullable Device d) throws Exception {
		if (d == null || d.component == null) return;
		LifecycleManager lifecycle = d.component.getLifecycleManager();
		lifecycle.stopServices();
		lifecycle.waitForShutdown();
		d.component = null;
	}

	private void addContact(Device d, Author remote, Identity local,
			boolean alice) throws Exception {
		ContactGroupScanTestComponent c = requireComponent(d);
		d.contactId = c.getContactManager().addContact(remote, local.getId(),
				getSecretKey(), now, alice, true, true);
		Group g = d.messagingManager.getContactGroup(
				c.getContactManager().getContact(d.contactId));
		d.group = g.getId();
		d.db.transaction(false, txn -> d.db.setGroupVisibility(txn,
				d.contactId, g.getId(), Group.Visibility.SHARED));
	}

	private static final class Settled extends Event {
		private final java.util.concurrent.CountDownLatch latch =
				new java.util.concurrent.CountDownLatch(1);
	}

	private static List<PinnedMessageChangedEvent> events(Device d)
			throws InterruptedException {
		Settled marker = new Settled();
		requireComponent(d).getEventBus().broadcast(marker);
		assertTrue(marker.latch.await(10,
				java.util.concurrent.TimeUnit.SECONDS));
		return d.pinEvents;
	}
}

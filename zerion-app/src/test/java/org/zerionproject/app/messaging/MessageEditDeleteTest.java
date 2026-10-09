package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.MessagingManager.EditResult;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.Collection;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_DELETE;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_EDIT;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_REACTION;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class MessageEditDeleteTest extends BrambleTestCase {

	private static final long HOUR = 60L * 60 * 1000;

	private final File testDir = getTestDirectory();

	private ContactGroupScanTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private MessagingManager messagingManager;
	private PrivateMessageValidator validator;
	private ContactId contact;
	private Group contactGroup;
	private final long now = System.currentTimeMillis();

	@Before
	public void setUp() throws Exception {
		File dir = new File(testDir, "device");
		assertTrue(dir.mkdirs());
		device = DaggerContactGroupScanTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(dir))
				.build();
		ContactGroupScanTestComponent.Helper.injectEagerSingletons(device);
		IdentityManager identityManager = device.getIdentityManager();
		Identity local = identityManager.createIdentity("Device");
		identityManager.registerIdentity(local);
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.startServices(getSecretKey());
		lifecycleManager.waitForStartup();
		ContactManager contactManager = device.getContactManager();
		contact = contactManager.addContact(
				identityManager.createIdentity("Contact").getLocalAuthor(),
				local.getId(), getSecretKey(), System.currentTimeMillis(),
				true, true, true);
		clientHelper = device.getClientHelper();
		db = device.getDatabaseComponent();
		messagingManager = device.getMessagingManager();
		validator = device.getPrivateMessageValidator();
		contactGroup = messagingManager.getContactGroup(
				contactManager.getContact(contact));
		db.transaction(false, txn -> db.setGroupVisibility(txn, contact,
				contactGroup.getId(), Group.Visibility.SHARED));
	}

	@After
	public void tearDown() throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
		deleteTestDirectory(testDir);
	}

	@Test
	public void aContactsEditReplacesTheTextAndMarksItEdited()
			throws Exception {
		MessageId target = deliverAt(text("Hey Allfs good"), now - HOUR);

		deliverAt(edit(target, "Hey all good"), now - HOUR + 1000);

		assertEquals("Hey all good", messagingManager.getMessageText(target));
		assertTrue(header(target).isEdited());
	}

	@Test
	public void anOlderEditNeverReplacesANewerOne() throws Exception {
		MessageId target = deliverAt(text("first"), now - HOUR);
		deliverAt(edit(target, "third"), now - HOUR + 3000);
		deliverAt(edit(target, "second"), now - HOUR + 2000);

		assertEquals("third", messagingManager.getMessageText(target));
	}

	@Test
	public void anEditMoreThanADayAfterSendingIsIgnored() throws Exception {
		long sent = now - 3 * 24 * HOUR;
		MessageId target = deliverAt(text("original"), sent);

		deliverAt(edit(target, "changed"), sent + 24 * HOUR + 1);

		assertEquals("original", messagingManager.getMessageText(target));
		assertFalse(header(target).isEdited());
	}

	@Test
	public void anEditThatArrivesBeforeItsMessageIsApplied()
			throws Exception {
		Message original = create(text("Hey Allfs good"), now - HOUR);
		deliverAt(edit(original.getId(), "Hey all good"), now - HOUR + 1000);

		deliver(original);

		assertEquals("Hey all good",
				messagingManager.getMessageText(original.getId()));
		assertTrue(header(original.getId()).isEdited());
	}

	@Test
	public void aContactCannotEditOrDeleteMyMessage() throws Exception {
		MessageId mine = own("mine", now - HOUR);

		deliverAt(edit(mine, "theirs"), now - HOUR + 1000);
		deliverAt(delete(mine), now - HOUR + 2000);

		assertEquals("mine", messagingManager.getMessageText(mine));
		assertTrue(conversation().contains(mine));
	}

	@Test
	public void specialMessagesCannotBeEditedOrBecomeSpecial()
			throws Exception {
		MessageId note = deliverAt(text("SECRET:hidden"), now - HOUR);
		deliverAt(edit(note, "visible"), now - HOUR + 1000);
		assertEquals("SECRET:hidden", messagingManager.getMessageText(note));

		Message m = create(edit(note, "VOICE_CALL:missed"), now - HOUR + 2000);
		try {
			validator.validateToBdf(m, contactGroup);
			fail("an edit may not turn a text into a call event");
		} catch (InvalidMessageException expected) {
		}
	}

	@Test
	public void aContactsDeleteRemovesTheMessageWithoutATrace()
			throws Exception {
		MessageId target = deliverAt(text("oops"), now - HOUR);
		deliverAt(reaction(target), now - HOUR + 500);

		deliverAt(delete(target), now - HOUR + 1000);

		assertFalse(conversation().contains(target));
		assertEquals(0, countOf(MESSAGE_REACTION));
	}

	@Test
	public void aMessageThatArrivesAfterItsDeleteIsRejected()
			throws Exception {
		Message original = create(text("oops"), now - HOUR);
		deliverAt(delete(original.getId()), now - HOUR + 1000);

		assertEquals(MessageState.INVALID, deliver(original));
		assertFalse(conversation().contains(original.getId()));
	}

	@Test
	public void anEditOfACaptionWhoseImageArrivesLastIsApplied()
			throws Exception {
		Message image = attachment(now - HOUR);
		Message captioned = create(BdfList.of(PRIVATE_MESSAGE, "Hey Allfs",
				BdfList.of(BdfList.of(image.getId().getBytes(), "image/jpeg"))),
				now - HOUR + 1);
		assertEquals(MessageState.PENDING, deliver(captioned));
		deliverAt(edit(captioned.getId(), "Hey all"), now - HOUR + 1000);

		assertEquals(MessageState.DELIVERED, deliver(image));

		awaitState(captioned.getId(), MessageState.DELIVERED);
		assertEquals("Hey all",
				messagingManager.getMessageText(captioned.getId()));
		assertTrue(header(captioned.getId()).isEdited());
	}

	@Test
	public void aDeleteWithdrawsAMessageStillWaitingForItsImage()
			throws Exception {
		Message image = attachment(now - HOUR);
		Message captioned = create(BdfList.of(PRIVATE_MESSAGE, "oops",
				BdfList.of(BdfList.of(image.getId().getBytes(), "image/jpeg"))),
				now - HOUR + 1);
		assertEquals(MessageState.PENDING, deliver(captioned));
		deliverAt(edit(captioned.getId(), "oops!"), now - HOUR + 500);

		deliverAt(delete(captioned.getId()), now - HOUR + 1000);
		deliver(image);

		assertEquals(MessageState.INVALID, state(captioned.getId()));
		assertFalse(conversation().contains(captioned.getId()));
		assertEquals(0, countOf(MESSAGE_EDIT));
	}

	@Test
	public void aMeshPhotoDeletedForEveryoneDoesNotComeBack()
			throws Exception {
		byte[] jpeg = new byte[] {1, 2, 3};
		long sent = now - HOUR;
		byte[] body = concat(clientHelper.toByteArray(
				BdfList.of(ATTACHMENT, "image/jpeg")), jpeg);
		Message attachment = clientHelper.createMessage(contactGroup.getId(),
				sent, body);
		Message photo = create(BdfList.of(PRIVATE_MESSAGE, null,
				BdfList.of(BdfList.of(attachment.getId().getBytes(),
						"image/jpeg"))), sent);
		deliverAt(delete(photo.getId()), sent + 1000);

		messagingManager.receiveMeshAttachment(contact, "image/jpeg", jpeg,
				sent);

		assertFalse(conversation().contains(photo.getId()));
	}

	@Test
	public void aMeshMessageIsOnlyDeletedOnThisDevice() throws Exception {
		MessageId mine = own("over the mesh", now - HOUR);
		db.transaction(false, txn -> clientHelper.mergeMessageMetadata(txn,
				mine, BdfDictionary.of(new BdfEntry("mesh", true))));

		assertFalse(senderSeeing(9).deleteForEveryone(contact,
				singletonList(mine)));

		assertFalse(conversation().contains(mine));
		assertEquals(0, localControls(MESSAGE_DELETE).size());
	}

	@Test
	public void everyEditOfAMessageGetsALaterTime() throws Exception {
		MessagingManagerImpl sender = senderSeeing(9);
		MessageId mine = own("one", now + HOUR);

		sender.editMessage(contact, mine, "two");
		long first = editedAt(mine);
		sender.editMessage(contact, mine, "three");

		assertTrue(editedAt(mine) > first);
		assertEquals("three", messagingManager.getMessageText(mine));
	}

	@Test
	public void editingSendsAnEditTheContactAccepts() throws Exception {
		MessagingManagerImpl sender = senderSeeing(9);
		MessageId mine = own("Hey Allfs good", now - HOUR);

		assertEquals(EditResult.EDITED,
				sender.editMessage(contact, mine, "Hey all good"));

		assertEquals("Hey all good", messagingManager.getMessageText(mine));
		assertTrue(header(mine).isEdited());
		Collection<MessageId> sent = localControls(MESSAGE_EDIT);
		assertEquals(1, sent.size());
		Message edit = db.transactionWithResult(true, txn ->
				db.getMessage(txn, sent.iterator().next()));
		assertNotNull(validator.validateToBdf(edit, contactGroup));
	}

	@Test
	public void aSecondEditReplacesTheFirstBeforeItIsSent()
			throws Exception {
		MessagingManagerImpl sender = senderSeeing(9);
		MessageId mine = own("one", now - HOUR);

		sender.editMessage(contact, mine, "two");
		sender.editMessage(contact, mine, "three");

		assertEquals(1, localControls(MESSAGE_EDIT).size());
		assertEquals("three", messagingManager.getMessageText(mine));
	}

	@Test
	public void editingIsRefusedWhenItCannotWork() throws Exception {
		MessageId mine = own("text", now - HOUR);
		assertEquals(EditResult.NOT_SUPPORTED_BY_CONTACT,
				senderSeeing(8).editMessage(contact, mine, "new"));

		MessageId old = own("old", now - 25 * HOUR);
		assertEquals(EditResult.TOO_LATE,
				senderSeeing(9).editMessage(contact, old, "new"));

		MessageId theirs = deliverAt(text("theirs"), now - HOUR);
		assertEquals(EditResult.NOT_EDITABLE,
				senderSeeing(9).editMessage(contact, theirs, "new"));

		assertEquals("text", messagingManager.getMessageText(mine));
		assertEquals(0, localControls(MESSAGE_EDIT).size());
	}

	@Test
	public void deletingASentMessageForEveryoneTellsTheContact()
			throws Exception {
		MessageId mine = own("oops", now - HOUR);
		markSent(mine);

		assertTrue(senderSeeing(9).deleteForEveryone(contact,
				singletonList(mine)));

		assertFalse(conversation().contains(mine));
		Collection<MessageId> sent = localControls(MESSAGE_DELETE);
		assertEquals(1, sent.size());
		Message delete = db.transactionWithResult(true, txn ->
				db.getMessage(txn, sent.iterator().next()));
		assertNotNull(validator.validateToBdf(delete, contactGroup));
	}

	@Test
	public void aMessageNeverSentIsSimplyWithdrawn() throws Exception {
		MessageId mine = own("not sent yet", now - HOUR);

		assertTrue(senderSeeing(9).deleteForEveryone(contact,
				singletonList(mine)));

		assertFalse(conversation().contains(mine));
		assertEquals(0, localControls(MESSAGE_DELETE).size());
	}

	@Test
	public void theSenderIsToldWhenTheContactCannotDelete()
			throws Exception {
		MessageId mine = own("oops", now - HOUR);
		markSent(mine);

		assertFalse(senderSeeing(8).deleteForEveryone(contact,
				singletonList(mine)));

		assertFalse(conversation().contains(mine));
		assertEquals(0, localControls(MESSAGE_DELETE).size());
	}

	private static BdfList text(String text) {
		return BdfList.of(PRIVATE_MESSAGE, text, new BdfList());
	}

	private static BdfList edit(MessageId target, String text) {
		return BdfList.of(MESSAGE_EDIT, target.getBytes(), text);
	}

	private static BdfList delete(MessageId target) {
		return BdfList.of(MESSAGE_DELETE, target.getBytes());
	}

	private static BdfList reaction(MessageId target) {
		return BdfList.of(MESSAGE_REACTION, target.getBytes(), "heart");
	}

	private MessagingManagerImpl senderSeeing(int minorVersion) {
		ClientVersioningManager real = device.getClientVersioningManager();
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
		return new MessagingManagerImpl(db, clientHelper, seen,
				device.getMetadataParser(), device.getConversationManager(),
				device.getMessageTracker(), device.getContactGroupFactory(),
				device.getAutoDeleteManager(),
				device.getStreamingAttachmentWriter(),
				device.getIdentityManager(), validator);
	}

	private MessageId own(String text, long ts) throws Exception {
		Message m = create(text(text), ts);
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry("timestamp", ts),
				new BdfEntry("local", true),
				new BdfEntry("read", true),
				new BdfEntry("messageType", PRIVATE_MESSAGE),
				new BdfEntry("hasText", true),
				new BdfEntry("attachmentHeaders", new BdfList()));
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, true, false));
		return m.getId();
	}

	private void markSent(MessageId id) throws Exception {
		db.transaction(false, txn -> {
			db.setGroupVisibility(txn, contact, contactGroup.getId(),
					Group.Visibility.SHARED);
			db.setMessagesSent(txn, contact, singletonList(id), 60_000L);
			assertTrue(db.getMessageStatus(txn, contact, id).isSent());
		});
	}

	private Collection<MessageId> localControls(int type) throws Exception {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry("messageType", type),
				new BdfEntry("local", true));
		return db.transactionWithResult(true, txn ->
				clientHelper.getMessageIds(txn, contactGroup.getId(), query));
	}

	private int countOf(int type) throws Exception {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry("messageType", type));
		return db.transactionWithResult(true, txn -> clientHelper
				.getMessageIds(txn, contactGroup.getId(), query).size());
	}

	private Collection<MessageId> conversation() throws Exception {
		return db.transactionWithResult(true, txn ->
				messagingManager.getMessageIds(txn, contact));
	}

	private PrivateMessageHeader header(MessageId id) throws Exception {
		Collection<ConversationMessageHeader> headers =
				db.transactionWithResult(true, txn ->
						messagingManager.getMessageHeaders(txn, contact));
		for (ConversationMessageHeader h : headers) {
			if (h.getId().equals(id)) return (PrivateMessageHeader) h;
		}
		throw new AssertionError("no header for the message");
	}

	private Message create(BdfList body, long ts) throws FormatException {
		return clientHelper.createMessage(contactGroup.getId(), ts,
				clientHelper.toByteArray(body));
	}

	private MessageId deliverAt(BdfList body, long ts) throws Exception {
		Message m = create(body, ts);
		deliver(m);
		return m.getId();
	}

	private MessageState deliver(Message m) throws Exception {
		db.transaction(false, txn -> db.receiveMessage(txn, contact, m));
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			MessageState s;
			try {
				s = state(m.getId());
			} catch (NoSuchMessageException removedAfterDelivery) {
				return MessageState.DELIVERED;
			}
			if (s != MessageState.UNKNOWN) {
				if (s != MessageState.PENDING) return s;
				if (System.currentTimeMillis() > deadline - 18_000) return s;
			}
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("the message was never validated");
			}
			Thread.sleep(20);
		}
	}

	private void awaitState(MessageId m, MessageState want) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (state(m) != want) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("still " + state(m));
			}
			Thread.sleep(20);
		}
	}

	private MessageState state(MessageId m) throws Exception {
		return db.transactionWithResult(true, txn ->
				db.getMessageState(txn, m));
	}

	private Message attachment(long ts) throws Exception {
		byte[] body = concat(clientHelper.toByteArray(
				BdfList.of(ATTACHMENT, "image/jpeg")), new byte[] {9, 9, 9});
		return clientHelper.createMessage(contactGroup.getId(), ts, body);
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = new byte[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	private long editedAt(MessageId m) throws Exception {
		return db.transactionWithResult(true, txn -> clientHelper
				.getMessageMetadataAsDictionary(txn, m).getLong("editedAt"));
	}
}

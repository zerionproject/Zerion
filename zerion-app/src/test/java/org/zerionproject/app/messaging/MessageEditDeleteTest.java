package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.cleanup.CleanupHook;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.zerionproject.core.api.sync.validation.MessageValidator;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.MessagingManager.EditResult;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertArrayEquals;
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
import static org.zerionproject.app.messaging.MessagingConstants.BODY_SALT_LENGTH;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class MessageEditDeleteTest extends BrambleTestCase {

	private static final long HOUR = 60L * 60 * 1000;

	private final File testDir = getTestDirectory();
	private final File dir = new File(testDir, "device");
	private final SecretKey dbKey = getSecretKey();

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
		assertTrue(dir.mkdirs());
		device = build();
		IdentityManager identityManager = device.getIdentityManager();
		Identity local = identityManager.createIdentity("Device");
		identityManager.registerIdentity(local);
		start();
		ContactManager contactManager = device.getContactManager();
		contact = contactManager.addContact(
				identityManager.createIdentity("Contact").getLocalAuthor(),
				local.getId(), getSecretKey(), System.currentTimeMillis(),
				true, true, true);
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
	public void anEditValidatedTwiceAtStartupStillWaitsForItsMessage()
			throws Exception {
		Message original = create(text("Hey Allfs good"), now - HOUR);
		Message edit = create(edit(original.getId(), "Hey all good"),
				now - HOUR + 1000);

		receiveWhileStarting(edit, false);

		assertEquals(MessageState.DELIVERED, state(edit.getId()));
		assertEquals(MessageState.DELIVERED, deliver(original));
		assertEquals("Hey all good",
				messagingManager.getMessageText(original.getId()));
		assertTrue(header(original.getId()).isEdited());
	}

	@Test
	public void aMessageValidatedTwiceAtStartupIsDeliveredOnceAndEditable()
			throws Exception {
		Message original = create(text("Hey Allfs good"), now - HOUR);

		receiveWhileStarting(original, false);

		assertEquals(MessageState.DELIVERED, state(original.getId()));
		assertEquals(1, device.getMessageTracker()
				.getGroupCount(contactGroup.getId()).getMsgCount());
		deliverAt(edit(original.getId(), "Hey all good"), now - HOUR + 1000);
		assertEquals("Hey all good",
				messagingManager.getMessageText(original.getId()));
		assertTrue(header(original.getId()).isEdited());
		assertEquals(1, device.getMessageTracker()
				.getGroupCount(contactGroup.getId()).getUnreadCount());
	}

	@Test
	public void aDeliveryDeferredWhileStartingWaitsForTheNextStart()
			throws Exception {
		Message m = create(text("Hey all good"), now - HOUR);

		receiveWhileStarting(m, true);

		assertEquals("a deferred message was delivered by its second "
				+ "validation", MessageState.PENDING, state(m.getId()));
		assertEquals(0, device.getMessageTracker()
				.getGroupCount(contactGroup.getId()).getMsgCount());
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
	public void anEditForACurrentContactCarriesASaltOfItsOwn()
			throws Exception {
		MessagingManagerImpl sender =
				senderSeeing(MessagingManager.MINOR_VERSION);
		MessageId mine = own("Hey Allfs good", now - HOUR);

		assertEquals(EditResult.EDITED,
				sender.editMessage(contact, mine, "Hey all good"));

		Collection<MessageId> sent = localControls(MESSAGE_EDIT);
		assertEquals(1, sent.size());
		Message edit = db.transactionWithResult(true, txn ->
				db.getMessage(txn, sent.iterator().next()));
		BdfList body = clientHelper.toList(edit);
		assertEquals(4, body.size());
		assertEquals("Hey all good", body.getString(2));
		assertEquals(BODY_SALT_LENGTH, body.getRaw(3).length);
		assertNotNull(validator.validateToBdf(edit, contactGroup));
	}

	@Test
	public void anEditForAContactOnAnEarlierVersionKeepsItsShape()
			throws Exception {
		int checked = 0;
		for (int minor : new int[] {9, 10, 11}) {
			MessageId mine = own("before " + minor, now - HOUR + minor);

			assertEquals(EditResult.EDITED, senderSeeing(minor)
					.editMessage(contact, mine, "after " + minor));

			for (MessageId id : localControls(MESSAGE_EDIT)) {
				Message edit = db.transactionWithResult(true, txn ->
						db.getMessage(txn, id));
				BdfList body = clientHelper.toList(edit);
				if (!body.getString(2).equals("after " + minor)) continue;
				assertEquals(3, body.size());
				assertEquals(MESSAGE_EDIT, (int) body.getInt(0));
				assertArrayEquals(mine.getBytes(), body.getRaw(1));
				checked++;
			}
		}
		assertEquals(3, checked);
	}

	@Test
	public void theMessageFormatFollowsTheContactsVersion() throws Exception {
		assertEquals(PrivateMessageFormat.TEXT_IMAGES_SALTED,
				formatSeenAt(MessagingManager.MINOR_VERSION));
		assertEquals(PrivateMessageFormat.TEXT_IMAGES_SALTED,
				formatSeenAt(MessagingManager.SALTED_TEXT_MIN_VERSION));
		for (int minor = 4; minor < MessagingManager.SALTED_TEXT_MIN_VERSION;
				minor++) {
			assertEquals(PrivateMessageFormat.TEXT_IMAGES_CHUNKED,
					formatSeenAt(minor));
		}
		assertEquals(PrivateMessageFormat.TEXT_IMAGES_AUTO_DELETE,
				formatSeenAt(3));
		assertEquals(PrivateMessageFormat.TEXT_IMAGES, formatSeenAt(1));
		assertEquals(PrivateMessageFormat.TEXT_ONLY, formatSeenAt(0));
		assertTrue(PrivateMessageFormat.TEXT_IMAGES_SALTED
				.supportsChunkedAttachments());
		assertFalse(PrivateMessageFormat.TEXT_IMAGES_CHUNKED.isSalted());
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

	@Test
	public void anEditWaitingToBeSentGoesWithItsExpiredMessage()
			throws Exception {
		MessageId mine = own("gone soon", now - HOUR);
		senderSeeing(9).editMessage(contact, mine, "gone soon too");
		assertEquals(1, localControls(MESSAGE_EDIT).size());

		db.transaction(false, txn -> ((CleanupHook) messagingManager)
				.deleteMessages(txn, contactGroup.getId(), singletonList(mine)));

		assertEquals(0, localControls(MESSAGE_EDIT).size());
	}

	@Test
	public void anEditWaitingToBeSentGoesWhenItsMessageIsDeletedHere()
			throws Exception {
		MessageId mine = own("oops", now - HOUR);
		senderSeeing(9).editMessage(contact, mine, "oops!");

		db.transaction(false, txn -> messagingManager.deleteMessages(txn,
				contact, singleton(mine)));

		assertEquals(0, localControls(MESSAGE_EDIT).size());
	}

	@Test
	public void aDeleteIsSentWhenOnlyAnEditOfTheMessageLeft()
			throws Exception {
		MessagingManagerImpl sender = senderSeeing(9);
		MessageId mine = own("draft", now - HOUR);
		sender.editMessage(contact, mine, "the real text");
		markSent(localControls(MESSAGE_EDIT).iterator().next());

		assertTrue(sender.deleteForEveryone(contact, singletonList(mine)));

		assertEquals(0, localControls(MESSAGE_EDIT).size());
		assertEquals(1, localControls(MESSAGE_DELETE).size());
	}

	@Test
	public void aDeleteDropsEditsWaitingForAMessageNotYetReceived()
			throws Exception {
		Message original = create(text("first draft"), now - HOUR);
		deliverAt(edit(original.getId(), "second draft"), now - HOUR + 500);
		assertEquals(1, countOf(MESSAGE_EDIT));

		deliverAt(delete(original.getId()), now - HOUR + 1000);

		assertEquals(0, countOf(MESSAGE_EDIT));
	}

	@Test
	public void anEditArrivingAfterItsMessageWasDeletedIsNotKept()
			throws Exception {
		MessageId target = deliverAt(text("oops"), now - HOUR);
		deliverAt(delete(target), now - HOUR + 2000);

		deliverAt(edit(target, "oops!"), now - HOUR + 1000);

		assertEquals(0, countOf(MESSAGE_EDIT));
	}

	@Test
	public void aWithdrawnMessageTakesTheImagesThatAlreadyArrived()
			throws Exception {
		Message first = attachment(now - HOUR);
		Message second = attachment(now - HOUR + 1);
		Message photos = create(BdfList.of(PRIVATE_MESSAGE, "two photos",
				BdfList.of(BdfList.of(first.getId().getBytes(), "image/jpeg"),
						BdfList.of(second.getId().getBytes(), "image/jpeg"))),
				now - HOUR + 2);
		assertEquals(MessageState.PENDING, deliver(photos));
		assertEquals(MessageState.DELIVERED, deliver(first));
		assertEquals(1, countOf(ATTACHMENT));

		deliverAt(delete(photos.getId()), now - HOUR + 1000);

		assertEquals(MessageState.INVALID, state(photos.getId()));
		assertEquals(0, countOf(ATTACHMENT));
	}

	@Test
	public void clearingTheChatKeepsADeleteForEveryoneNotYetDelivered()
			throws Exception {
		MessageId mine = own("oops", now - HOUR);
		markSent(mine);
		assertTrue(senderSeeing(9).deleteForEveryone(contact,
				singletonList(mine)));
		Set<MessageId> pending = new HashSet<>(localControls(MESSAGE_DELETE));
		assertEquals(1, pending.size());

		db.transaction(false, txn ->
				messagingManager.deleteAllMessages(txn, contact));

		assertEquals(pending, new HashSet<>(localControls(MESSAGE_DELETE)));
		db.transaction(true, txn -> assertFalse(db.getMessageStatus(txn,
				contact, pending.iterator().next()).isSeen()));
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
				device.getIdentityManager(), validator,
				device.getCryptoComponent());
	}

	private PrivateMessageFormat formatSeenAt(int minorVersion)
			throws Exception {
		MessagingManagerImpl seen = senderSeeing(minorVersion);
		return db.transactionWithResult(true, txn ->
				seen.getContactMessageFormat(txn, contact));
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

	private ContactGroupScanTestComponent build() {
		ContactGroupScanTestComponent c =
				DaggerContactGroupScanTestComponent.builder()
						.testDatabaseConfigModule(
								new TestDatabaseConfigModule(dir))
						.build();
		ContactGroupScanTestComponent.Helper.injectEagerSingletons(c);
		return c;
	}

	private void start() throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.startServices(dbKey);
		lifecycleManager.waitForStartup();
		clientHelper = device.getClientHelper();
		db = device.getDatabaseComponent();
		messagingManager = device.getMessagingManager();
		validator = device.getPrivateMessageValidator();
	}

	private void receiveWhileStarting(Message m, boolean deferOnce)
			throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
		device = build();
		Executor dbExecutor = device.getDatabaseExecutor();
		CountDownLatch starting = new CountDownLatch(1);
		dbExecutor.execute(() -> {
			try {
				starting.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		start();
		AtomicInteger validations = new AtomicInteger();
		MessageValidator real = validator;
		device.getValidationManager().registerMessageValidator(
				MessagingManager.CLIENT_ID, MessagingManager.MAJOR_VERSION,
				(msg, g) -> {
					try {
						return real.validateMessage(msg, g);
					} finally {
						if (msg.getId().equals(m.getId())) {
							validations.incrementAndGet();
						}
					}
				});
		if (deferOnce) {
			IncomingMessageHook hook = (IncomingMessageHook) messagingManager;
			AtomicInteger deliveries = new AtomicInteger();
			device.getValidationManager().registerIncomingMessageHook(
					MessagingManager.CLIENT_ID, MessagingManager.MAJOR_VERSION,
					(txn, msg, meta) -> {
						if (msg.getId().equals(m.getId())
								&& deliveries.getAndIncrement() == 0) {
							throw new DbException();
						}
						return hook.incomingMessage(txn, msg, meta);
					});
		}
		db.transaction(false, txn -> db.receiveMessage(txn, contact, m));
		starting.countDown();
		long deadline = System.currentTimeMillis() + 20_000;
		while (validations.get() < 2) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("validated " + validations.get()
						+ " times");
			}
			Thread.sleep(5);
		}
		drain(dbExecutor);
		drain(dbExecutor);
	}

	private static void drain(Executor e) throws InterruptedException {
		CountDownLatch done = new CountDownLatch(1);
		e.execute(done::countDown);
		assertTrue(done.await(20, SECONDS));
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

package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.cleanup.CleanupHook;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.MessageDeletedException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.event.PurgeRequestedEvent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_INVITE_OFFER;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_ADDED;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_REMOVED;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_POST;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_SETTINGS;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_DELETE;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_EDIT;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_REACTION;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_REACTION_REMOVED;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.app.messaging.MessageTypes.TYPING_INDICATOR;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class DeletedContentTest extends BrambleTestCase {

	private static final long HOUR = 60L * 60 * 1000;

	private final File testDir = getTestDirectory();
	private final AtomicInteger purgeRequests = new AtomicInteger();
	private final EventListener purgeListener = e -> {
		if (e instanceof PurgeRequestedEvent) purgeRequests.incrementAndGet();
	};

	private ContactGroupScanTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private EventBus eventBus;
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
		eventBus = device.getEventBus();
		messagingManager = device.getMessagingManager();
		validator = device.getPrivateMessageValidator();
		contactGroup = messagingManager.getContactGroup(
				contactManager.getContact(contact));
		db.transaction(false, txn -> db.setGroupVisibility(txn, contact,
				contactGroup.getId(), Group.Visibility.SHARED));
		eventBus.addListener(purgeListener);
	}

	@After
	public void tearDown() throws Exception {
		eventBus.removeListener(purgeListener);
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
		deleteTestDirectory(testDir);
	}

	@Test
	public void deletingForMeAlsoRemovesTheReactionsToTheMessage()
			throws Exception {
		MessageId target = deliverAt(text("hello"), now - HOUR);
		deliverAt(reaction(target), now - HOUR + 500);
		assertTrue(messagingManager.addLocalReaction(contact, target,
				"thumbs"));
		assertEquals(2, countOf(MESSAGE_REACTION));

		db.transaction(false, txn -> messagingManager.deleteMessages(txn,
				contact, singleton(target)));

		assertFalse(isStored(target));
		assertEquals(0, countOf(MESSAGE_REACTION));
		assertFalse(messagingManager.getReactions(contact)
				.containsKey(target));
	}

	@Test
	public void aReactionRemovalNotYetAckedOutlivesADeletionForMe()
			throws Exception {
		MessagingManagerImpl sender = senderSeeing(9);
		MessageId target = deliverAt(text("hello"), now - HOUR);
		sender.addLocalReaction(contact, target, "thumbs");
		sender.addLocalReaction(contact, target, "thumbs");
		assertEquals(0, countOf(MESSAGE_REACTION));
		assertEquals(1, localControls(MESSAGE_REACTION_REMOVED).size());

		db.transaction(false, txn -> sender.deleteMessages(txn, contact,
				singleton(target)));

		assertFalse(isStored(target));
		assertEquals("the contact must still learn the reaction is gone",
				1, localControls(MESSAGE_REACTION_REMOVED).size());
	}

	@Test
	public void anAckedReactionRemovalGoesWithADeletionForMe()
			throws Exception {
		MessagingManagerImpl sender = senderSeeing(9);
		MessageId target = deliverAt(text("hello"), now - HOUR);
		sender.addLocalReaction(contact, target, "thumbs");
		sender.addLocalReaction(contact, target, "thumbs");
		MessageId removal =
				localControls(MESSAGE_REACTION_REMOVED).iterator().next();
		db.transaction(false, txn -> db.receiveAck(txn, contact,
				new Ack(singletonList(removal))));

		db.transaction(false, txn -> sender.deleteMessages(txn, contact,
				singleton(target)));

		assertEquals(0, localControls(MESSAGE_REACTION_REMOVED).size());
		assertFalse(isStored(removal));
	}

	@Test
	public void clearingTheChatKeepsTheGroupRecordsOfThePairwiseGroup()
			throws Exception {
		MessageId text = deliverAt(text("hello"), now - HOUR);
		MessageId reaction = deliverAt(reaction(text), now - HOUR + 1);
		MessageId mine = own("mine", now - HOUR + 2);
		MessageId typing = storeLocal(TYPING_INDICATOR, now - HOUR + 3,
				new BdfDictionary());
		MessageId pendingEdit = deliverAt(edit(new MessageId(getRandomId()),
				"edit for a message not here"), now - HOUR + 4);
		byte[] grouptrGroupId = getRandomId();
		List<MessageId> groupRecords = new ArrayList<>();
		int[] types = {GROUP_POST, GROUP_MEMBER_ADDED, GROUP_SETTINGS,
				GROUPTR_INVITE_OFFER, MessageTypes.GROUP_POST_DELETE};
		for (int i = 0; i < types.length; i++) {
			groupRecords.add(storeLocal(types[i], now - HOUR + 10 + i,
					BdfDictionary.of(new BdfEntry("groupId",
							grouptrGroupId))));
		}

		db.transaction(false, txn ->
				messagingManager.deleteAllMessages(txn, contact));

		for (MessageId gone : new MessageId[] {text, reaction, mine, typing,
				pendingEdit}) {
			assertFalse(isStored(gone));
		}
		assertEquals(0, countOf(MESSAGE_EDIT));
		for (MessageId kept : groupRecords) assertTrue(isStored(kept));
		assertTrue(conversation().isEmpty());
	}

	@Test
	public void clearingTheChatKeepsGroupRecordsTheContactHasNotReceived()
			throws Exception {
		GroupTrManager groups = device.getGroupTrManager();
		byte[] contactKey = device.getContactManager().getContact(contact)
				.getAuthor().getPublicKey().getEncoded();
		byte[] groupId = groups.createGroup("Group").getGroupId();
		Collection<MessageId> before = allRecords();
		groups.addMember(groupId, contactKey, "Contact");
		groups.removeMember(groupId, contactKey);
		List<MessageId> dispatched = new ArrayList<>(allRecords());
		dispatched.removeAll(before);
		assertFalse(dispatched.isEmpty());
		MessageId unsent = own("not sent yet", now - HOUR);

		db.transaction(false, txn ->
				messagingManager.deleteAllMessages(txn, contact));

		for (MessageId record : dispatched) {
			assertTrue("a group record the contact has not received was"
					+ " deleted", isStored(record));
		}
		assertFalse(isStored(unsent));
	}

	@Test
	public void clearingTheChatKeepsUntypedRecordsUntilTheContactHasThem()
			throws Exception {
		MessageId waiting = storeUntyped(new BdfDictionary(), now - HOUR,
				true);
		MessageId meshWaiting = storeUntyped(BdfDictionary.of(new BdfEntry(
				MessagingManager.MSG_KEY_MESH_GROUP_PENDING, true)),
				now - HOUR + 1, false);
		MessageId received = storeUntyped(new BdfDictionary(),
				now - HOUR + 2, true);
		db.transaction(false, txn -> db.receiveAck(txn, contact,
				new Ack(singletonList(received))));

		db.transaction(false, txn ->
				messagingManager.deleteAllMessages(txn, contact));

		assertTrue(isStored(waiting));
		assertTrue(isStored(meshWaiting));
		assertFalse(isStored(received));
	}

	@Test
	public void clearingTheChatKeepsOnlyInvitationsThatCanStillBeAnswered()
			throws Exception {
		byte[] open = getRandomId();
		byte[] answered = getRandomId();
		byte[] memberOpen = getRandomId();
		MessageId openCard = storeReceived(
				MessageTypes.GROUPTR_INVITE_OFFER, now - HOUR, open);
		MessageId answeredCard = storeReceived(
				MessageTypes.GROUPTR_INVITE_OFFER, now - HOUR + 1, answered);
		MessageId memberCard = storeReceived(
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER, now - HOUR + 2,
				memberOpen);
		MessageId memberAnswered = storeReceived(
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER, now - HOUR + 3,
				open);
		messagingManager.setInvitationCards((txn, type, gid) ->
				type == MessageTypes.GROUPTR_INVITE_OFFER
						? java.util.Arrays.equals(gid, open)
						: java.util.Arrays.equals(gid, memberOpen));
		try {
			db.transaction(false, txn ->
					messagingManager.deleteAllMessages(txn, contact));
		} finally {
			messagingManager.setInvitationCards(null);
		}

		assertTrue(isStored(openCard));
		assertFalse("an answered invitation leaves with the chat",
				isStored(answeredCard));
		assertTrue(isStored(memberCard));
		assertFalse(isStored(memberAnswered));
	}

	@Test
	public void clearingTheChatDoesNotBringBackAMessageDeletedForEveryone()
			throws Exception {
		Message original = create(text("oops"), now - HOUR);
		deliverAt(delete(original.getId()), now - HOUR + 1000);

		db.transaction(false, txn ->
				messagingManager.deleteAllMessages(txn, contact));

		assertEquals(MessageState.INVALID, deliver(original));
		assertFalse(conversation().contains(original.getId()));
	}

	@Test
	public void everyDeletionAsksForAPurgeOnceCommitted() throws Exception {
		MessageId forMe = deliverAt(text("for me"), now - HOUR);
		assertPurgeRequested(() -> db.transaction(false, txn ->
				messagingManager.deleteMessages(txn, contact,
						singleton(forMe))));

		MessageId mine = own("oops", now - HOUR + 1);
		markSent(mine);
		assertPurgeRequested(() -> senderSeeing(9).deleteForEveryone(contact,
				singletonList(mine)));

		MessageId theirs = deliverAt(text("theirs"), now - HOUR + 2);
		assertPurgeRequested(() -> deliverAt(delete(theirs), now - HOUR + 3));

		Message image = attachment(now - HOUR + 4);
		Message captioned = create(BdfList.of(PRIVATE_MESSAGE, "waiting",
				BdfList.of(BdfList.of(image.getId().getBytes(),
						"image/jpeg"))), now - HOUR + 5);
		assertEquals(MessageState.PENDING, deliver(captioned));
		assertPurgeRequested(() -> deliverAt(delete(captioned.getId()),
				now - HOUR + 6));
		assertEquals(MessageState.INVALID, state(captioned.getId()));

		MessageId expiring = deliverAt(text("expiring"), now - HOUR + 7);
		assertPurgeRequested(() -> db.transaction(false, txn ->
				((CleanupHook) messagingManager).deleteMessages(txn,
						contactGroup.getId(), singletonList(expiring))));

		deliverAt(text("cleared"), now - HOUR + 8);
		assertPurgeRequested(() -> db.transaction(false, txn ->
				messagingManager.deleteAllMessages(txn, contact)));
	}

	@Test
	public void everyEditAsksForAPurgeOnceCommitted() throws Exception {
		MessageId mine = own("first draft", now - HOUR);
		assertPurgeRequested(() -> senderSeeing(9).editMessage(contact, mine,
				"second draft"));

		MessageId theirs = deliverAt(text("their draft"), now - HOUR + 1);
		assertPurgeRequested(() -> deliverAt(edit(theirs, "their text"),
				now - HOUR + 2));
	}

	@Test
	public void readingAndWritingAloneAsksForNoPurge() throws Exception {
		waitForEvents();
		int before = purgeRequests.get();

		deliverAt(text("kept"), now - HOUR);
		own("kept too", now - HOUR + 1);
		conversation();

		waitForEvents();
		assertEquals(before, purgeRequests.get());
	}

	private interface Action {
		void run() throws Exception;
	}

	private void assertPurgeRequested(Action action) throws Exception {
		waitForEvents();
		int before = purgeRequests.get();
		action.run();
		waitForEvents();
		assertTrue("the deletion asked for no purge",
				purgeRequests.get() > before);
	}

	private void waitForEvents() throws Exception {
		CountDownLatch delivered = new CountDownLatch(1);
		Event barrier = new Event() {
		};
		EventListener listener = e -> {
			if (e == barrier) delivered.countDown();
		};
		eventBus.addListener(listener);
		eventBus.broadcast(barrier);
		assertTrue(delivered.await(10, SECONDS));
		eventBus.removeListener(listener);
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

	private MessageId storeLocal(int type, long ts, BdfDictionary extra)
			throws Exception {
		Message m = create(BdfList.of(type, getRandomId()), ts);
		BdfDictionary meta = new BdfDictionary();
		meta.putAll(extra);
		meta.put("timestamp", ts);
		meta.put("local", true);
		meta.put("read", true);
		meta.put("messageType", type);
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, true, false));
		return m.getId();
	}

	private MessageId storeUntyped(BdfDictionary meta, long ts,
			boolean shared) throws Exception {
		Message m = create(BdfList.of((long) GROUP_MEMBER_REMOVED,
				getRandomId()), ts);
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, shared, false));
		return m.getId();
	}

	private Collection<MessageId> allRecords() throws Exception {
		return db.transactionWithResult(true, txn ->
				db.getAllMessageIds(txn, contactGroup.getId()));
	}

	private MessageId storeReceived(int type, long ts, byte[] groupTrGroupId)
			throws Exception {
		Message m = create(BdfList.of(type, getRandomId()), ts);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MessagingConstants.MSG_KEY_GROUP_ID, groupTrGroupId);
		meta.put("timestamp", ts);
		meta.put("local", false);
		meta.put("read", false);
		meta.put("messageType", type);
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, true, false));
		return m.getId();
	}

	private void markSent(MessageId id) throws Exception {
		db.transaction(false, txn -> {
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

	private boolean isStored(MessageId m) throws Exception {
		return db.transactionWithResult(true, txn -> {
			try {
				db.getMessage(txn, m);
				return true;
			} catch (NoSuchMessageException e) {
				return false;
			} catch (MessageDeletedException e) {
				return true;
			}
		});
	}

	private Message create(BdfList body, long ts) throws FormatException {
		return clientHelper.createMessage(contactGroup.getId(), ts,
				clientHelper.toByteArray(body));
	}

	private Message attachment(long ts) throws Exception {
		byte[] header = clientHelper.toByteArray(
				BdfList.of(ATTACHMENT, "image/jpeg"));
		byte[] body = new byte[header.length + 3];
		System.arraycopy(header, 0, body, 0, header.length);
		body[header.length] = 9;
		return clientHelper.createMessage(contactGroup.getId(), ts, body);
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

	private MessageState state(MessageId m) throws Exception {
		return db.transactionWithResult(true, txn ->
				db.getMessageState(txn, m));
	}
}

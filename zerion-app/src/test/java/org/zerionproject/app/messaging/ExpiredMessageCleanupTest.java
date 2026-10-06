package org.zerionproject.app.messaging;

import org.zerionproject.core.api.cleanup.CleanupHook;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.system.TimeTravelModule;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.core.test.TimeTravel;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.client.MessageTracker.GroupCount;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.conversation.DeletionResult;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.VoiceSignal;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.api.messaging.MessagingManager.MSG_KEY_MESH_GROUP_PENDING;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_ADDED;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.app.messaging.MessageTypes.VOICE_SIGNAL;
import static org.zerionproject.core.api.cleanup.CleanupManager.BATCH_DELAY_MS;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class ExpiredMessageCleanupTest extends BrambleTestCase {

	private static final long TIMER = SECONDS.toMillis(10);
	private static final long VOICE_SIGNAL_PURGE = MINUTES.toMillis(5);

	private final File testDir = getTestDirectory();

	private ContactGroupScanTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private MessagingManager messagingManager;
	private ConversationManager conversationManager;
	private MessageTracker messageTracker;
	private PrivateMessageValidator validator;
	private TimeTravel timeTravel;
	private EventBus eventBus;
	private ContactId healthy;
	private ContactId withRecords;
	private Group healthyGroup;
	private Group recordGroup;
	private long now;
	private long lastTimestamp = 0;

	@Before
	public void setUp() throws Exception {
		File dir = new File(testDir, "device");
		assertTrue(dir.mkdirs());
		device = DaggerContactGroupScanTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		ContactGroupScanTestComponent.Helper.injectEagerSingletons(device);
		timeTravel = device.getTimeTravel();
		now = System.currentTimeMillis();
		timeTravel.setCurrentTimeMillis(now);

		IdentityManager identityManager = device.getIdentityManager();
		Identity local = identityManager.createIdentity("Device");
		identityManager.registerIdentity(local);
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.startServices(getSecretKey());
		lifecycleManager.waitForStartup();

		ContactManager contactManager = device.getContactManager();
		healthy = contactManager.addContact(
				identityManager.createIdentity("Healthy").getLocalAuthor(),
				local.getId(), getSecretKey(), now, true, true, true);
		withRecords = contactManager.addContact(
				identityManager.createIdentity("Records").getLocalAuthor(),
				local.getId(), getSecretKey(), now, true, true, true);

		clientHelper = device.getClientHelper();
		db = device.getDatabaseComponent();
		messagingManager = device.getMessagingManager();
		conversationManager = device.getConversationManager();
		messageTracker = device.getMessageTracker();
		validator = device.getPrivateMessageValidator();
		eventBus = device.getEventBus();
		healthyGroup = messagingManager.getContactGroup(
				contactManager.getContact(healthy));
		recordGroup = messagingManager.getContactGroup(
				contactManager.getContact(withRecords));
		passTime(BATCH_DELAY_MS);
	}

	@After
	public void tearDown() throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
		deleteTestDirectory(testDir);
	}

	@Test
	public void theRecountLeavesOutGroupRecordsKeptForTheMesh()
			throws Exception {
		MessageId read = receiveText(recordGroup);
		receiveText(recordGroup);
		MessageId expired = receiveText(recordGroup);
		db.transaction(false, txn -> messageTracker.setReadFlag(txn,
				recordGroup.getId(), read, true));
		keepForTheMesh(recordGroup, true);
		keepForTheMesh(recordGroup, false);

		db.transaction(false, txn -> ((CleanupHook) messagingManager)
				.deleteMessages(txn, recordGroup.getId(),
						singletonList(expired)));

		GroupCount count = messageTracker.getGroupCount(recordGroup.getId());
		assertEquals(2, count.getMsgCount());
		assertEquals(1, count.getUnreadCount());
		Collection<ConversationMessageHeader> shown =
				db.transactionWithResult(true, txn ->
						messagingManager.getMessageHeaders(txn, withRecords));
		int unread = 0;
		for (ConversationMessageHeader h : shown) if (!h.isRead()) unread++;
		assertEquals(shown.size(), count.getMsgCount());
		assertEquals(unread, count.getUnreadCount());
	}

	@Test
	public void deletingMessagesTheChatDoesNotCountLeavesTheCountsAlone()
			throws Exception {
		receiveText(recordGroup);
		MessageId record = keepForTheMesh(recordGroup, false);
		GroupCount before = messageTracker.getGroupCount(recordGroup.getId());

		db.transaction(false, txn -> ((CleanupHook) messagingManager)
				.deleteMessages(txn, recordGroup.getId(),
						singletonList(record)));

		assertFalse(isStored(record));
		GroupCount after = messageTracker.getGroupCount(recordGroup.getId());
		assertEquals(before.getMsgCount(), after.getMsgCount());
		assertEquals(before.getUnreadCount(), after.getUnreadCount());
		assertEquals(before.getLatestMsgTime(), after.getLatestMsgTime());
	}

	@Test
	public void aUserCanDeleteFromAChatThatHoldsAGroupRecordKeptForTheMesh()
			throws Exception {
		MessageId deleted = receiveText(recordGroup);
		MessageId kept = receiveText(recordGroup);
		MessageId record = keepForTheMesh(recordGroup, false);

		DeletionResult result = conversationManager.deleteMessages(
				withRecords, singletonList(deleted));

		assertTrue(result.allDeleted());
		assertFalse(isStored(deleted));
		assertTrue(isStored(kept));
		assertTrue(isStored(record));
		GroupCount count = messageTracker.getGroupCount(recordGroup.getId());
		assertEquals(1, count.getMsgCount());
		assertEquals(1, count.getUnreadCount());
	}

	@Test
	public void expiredMessagesAreDeletedOnTimeInEveryChat()
			throws Exception {
		MessageId attachment = receiveAttachment(healthyGroup);
		MessageId expiring = receive(healthyGroup, BdfList.of(PRIVATE_MESSAGE,
				"with a photo", BdfList.of(BdfList.of(attachment.getBytes(),
						"image/jpeg"))));
		MessageId kept = receiveText(healthyGroup);
		MessageId voiceSignal = receive(healthyGroup,
				BdfList.of(VOICE_SIGNAL, 1, "call", "key material"));
		MessageId expiringWithRecords = receiveText(recordGroup);
		MessageId keptWithRecords = receiveText(recordGroup);
		MessageId pending = keepForTheMesh(recordGroup, true);
		MessageId shared = keepForTheMesh(recordGroup, false);
		expireIn(expiring, TIMER);
		expireIn(expiringWithRecords, TIMER);
		expireIn(voiceSignal, VOICE_SIGNAL_PURGE);

		passTime(TIMER + BATCH_DELAY_MS);

		assertFalse("the expired message of the healthy chat is kept",
				isStored(expiring));
		assertFalse("the attachment of the expired message is kept",
				isStored(attachment));
		assertFalse("the expired message of the chat with records is kept",
				isStored(expiringWithRecords));
		assertStored(kept, voiceSignal, keptWithRecords, pending, shared);
		assertEquals(1, messageTracker.getGroupCount(healthyGroup.getId())
				.getMsgCount());
		assertEquals(1, messageTracker.getGroupCount(recordGroup.getId())
				.getMsgCount());

		passTime(VOICE_SIGNAL_PURGE - TIMER);

		assertFalse("the voice signal is kept past its purge",
				isStored(voiceSignal));
		assertStored(kept, keptWithRecords, pending, shared);
	}

	@Test
	public void aVoiceSignalWeSendIsPurgedLikeAReceivedOne()
			throws Exception {
		Message m = clientHelper.createMessage(healthyGroup.getId(),
				now,
				BdfList.of(VOICE_SIGNAL, 0, "call", "key material"));
		messagingManager.addLocalVoiceSignal(new VoiceSignal(m,
				VoiceSignalType.CALL_OFFER, "call", "key material"));
		MessageId kept = receiveText(healthyGroup);
		assertStored(m.getId(), kept);

		passTime(VOICE_SIGNAL_PURGE - TIMER);
		assertStored(m.getId(), kept);

		passTime(TIMER + BATCH_DELAY_MS);
		assertFalse("the sent voice signal is kept past its purge",
				isStored(m.getId()));
		assertStored(kept);
	}

	@Test
	public void aChatWhoseCleanupFailsHoldsBackNoOtherChat()
			throws Exception {
		ClientId client = new ClientId("org.zerionproject.test.cleanup");
		Group failingGroup = device.getContactGroupFactory()
				.createLocalGroup(client, 0);
		db.transaction(false, txn -> db.addGroup(txn, failingGroup));
		AtomicBoolean failing = new AtomicBoolean(true);
		List<Collection<MessageId>> handedToHook = new ArrayList<>();
		device.getCleanupManager().registerCleanupHook(client, 0,
				(txn, g, ids) -> {
					synchronized (handedToHook) {
						handedToHook.add(new ArrayList<>(ids));
					}
					if (failing.get()) throw new DbException();
					for (MessageId m : ids) db.removeMessage(txn, m);
				});
		MessageId stuck = store(failingGroup.getId(), new BdfList(),
				BdfDictionary.of(new BdfEntry("kept", true)));
		MessageId expiring = receiveText(healthyGroup);
		MessageId expiringLater = receiveText(healthyGroup);
		MessageId kept = receiveText(healthyGroup);
		expireIn(stuck, TIMER);
		expireIn(expiring, TIMER);
		expireIn(expiringLater, 3 * TIMER);

		passTime(TIMER + BATCH_DELAY_MS);

		assertFalse("the expired message of the healthy chat is kept"
				+ " while another chat's cleanup fails", isStored(expiring));
		assertStored(stuck, expiringLater, kept);

		passTime(2 * TIMER);

		assertFalse("a later deadline is missed after a failed cleanup",
				isStored(expiringLater));
		assertStored(stuck, kept);

		failing.set(false);
		passTime(MINUTES.toMillis(2));

		assertFalse("the failed cleanup is not tried again",
				isStored(stuck));
		assertTrue(isStored(kept));
		synchronized (handedToHook) {
			assertTrue(handedToHook.size() >= 2);
			for (Collection<MessageId> ids : handedToHook) {
				assertEquals(singletonList(stuck), ids);
			}
		}
	}

	@Test
	public void aChatWhoseCleanupThrowsAnErrorHoldsBackNoOtherChat()
			throws Exception {
		ClientId client = new ClientId("org.zerionproject.test.cleanup.error");
		Group erringGroup = device.getContactGroupFactory()
				.createLocalGroup(client, 0);
		db.transaction(false, txn -> db.addGroup(txn, erringGroup));
		AtomicBoolean erring = new AtomicBoolean(true);
		List<Collection<MessageId>> handedToHook = new ArrayList<>();
		device.getCleanupManager().registerCleanupHook(client, 0,
				(txn, g, ids) -> {
					synchronized (handedToHook) {
						handedToHook.add(new ArrayList<>(ids));
					}
					if (erring.get()) throw new HookError();
					for (MessageId m : ids) db.removeMessage(txn, m);
				});
		MessageId stuck = store(erringGroup.getId(), new BdfList(),
				BdfDictionary.of(new BdfEntry("kept", true)));
		MessageId expiring = receiveText(healthyGroup);
		MessageId expiringLater = receiveText(healthyGroup);
		MessageId kept = receiveText(healthyGroup);
		expireIn(stuck, TIMER);
		expireIn(expiring, TIMER);
		expireIn(expiringLater, 3 * TIMER);

		passTime(TIMER + BATCH_DELAY_MS);

		assertFalse("the expired message of the healthy chat is kept"
				+ " while another chat's cleanup throws an error",
				isStored(expiring));
		assertStored(stuck, expiringLater, kept);

		passTime(2 * TIMER);

		assertFalse("a later deadline is missed after a cleanup error",
				isStored(expiringLater));
		assertStored(stuck, kept);

		erring.set(false);
		passTime(MINUTES.toMillis(2));

		assertFalse("the cleanup is not tried again after an error",
				isStored(stuck));
		assertTrue(isStored(kept));
		synchronized (handedToHook) {
			assertTrue(handedToHook.size() >= 2);
			for (Collection<MessageId> ids : handedToHook) {
				assertEquals(singletonList(stuck), ids);
			}
		}
	}

	private void passTime(long millis) throws Exception {
		waitForEvents();
		now += millis;
		timeTravel.setCurrentTimeMillis(now);
		waitForEvents();
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

	private void expireIn(MessageId m, long duration) throws Exception {
		db.transaction(false, txn -> {
			db.setCleanupTimerDuration(txn, m, duration);
			db.startCleanupTimer(txn, m);
		});
	}

	private MessageId receiveText(Group g) throws Exception {
		return receive(g, BdfList.of(PRIVATE_MESSAGE, "text " + now,
				new BdfList()));
	}

	private MessageId receive(Group g, BdfList body) throws Exception {
		return receive(g, clientHelper.toByteArray(body));
	}

	private MessageId receiveAttachment(Group g) throws Exception {
		byte[] descriptor = clientHelper.toByteArray(
				BdfList.of(ATTACHMENT, "image/jpeg"));
		byte[] data = getRandomBytes(1000);
		byte[] body = new byte[descriptor.length + data.length];
		System.arraycopy(descriptor, 0, body, 0, descriptor.length);
		System.arraycopy(data, 0, body, descriptor.length, data.length);
		return receive(g, body);
	}

	private MessageId receive(Group g, byte[] body) throws Exception {
		Message m = clientHelper.createMessage(g.getId(), nextTimestamp(),
				body);
		BdfDictionary meta = validator.validateToBdf(m, g).getDictionary();
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, false, false));
		return m.getId();
	}

	private MessageId keepForTheMesh(Group g, boolean pending)
			throws Exception {
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_MESH_GROUP_PENDING, pending);
		return store(g.getId(), BdfList.of(GROUP_MEMBER_ADDED, getRandomId(),
				now, getRandomBytes(64)), meta);
	}

	private MessageId store(GroupId g, BdfList body, BdfDictionary meta)
			throws Exception {
		Message m = clientHelper.createMessage(g, nextTimestamp(),
				clientHelper.toByteArray(body));
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, false, false));
		return m.getId();
	}

	private long nextTimestamp() {
		lastTimestamp = Math.max(lastTimestamp + 1, now - 60_000L);
		return lastTimestamp;
	}

	private void assertStored(MessageId... ids) throws Exception {
		for (MessageId m : asList(ids)) assertTrue(isStored(m));
	}

	private boolean isStored(MessageId m) throws Exception {
		return db.transactionWithResult(true, txn -> {
			try {
				db.getMessage(txn, m);
				return true;
			} catch (NoSuchMessageException e) {
				return false;
			}
		});
	}

	private static class HookError extends Error {
	}
}

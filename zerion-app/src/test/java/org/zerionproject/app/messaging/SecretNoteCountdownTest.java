package org.zerionproject.app.messaging;

import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.system.TimeTravelModule;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.core.test.TimeTravel;
import org.zerionproject.core.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.app.messaging.MessagingManagerImpl.SECRET_NOTES_NAMESPACE;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class SecretNoteCountdownTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();
	private final File dir = new File(testDir, "device");
	private final SecretKey key = getSecretKey();

	private ContactGroupScanTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private MessagingManager messagingManager;
	private ConversationManager conversationManager;
	private PrivateMessageValidator validator;
	private TimeTravel timeTravel;
	private EventBus eventBus;
	private ContactId alice;
	private ContactId bob;
	private Group aliceGroup;
	private Group bobGroup;
	private long now;
	private long lastTimestamp = 0;

	@Before
	public void setUp() throws Exception {
		assertTrue(dir.mkdirs());
		now = System.currentTimeMillis();
		IdentityManager identityManager = start();
		Identity local = identityManager.createIdentity("Device");
		identityManager.registerIdentity(local);
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.startServices(key);
		lifecycleManager.waitForStartup();
		ContactManager contactManager = device.getContactManager();
		alice = contactManager.addContact(
				identityManager.createIdentity("Alice").getLocalAuthor(),
				local.getId(), getSecretKey(), now, true, true, true);
		bob = contactManager.addContact(
				identityManager.createIdentity("Bob").getLocalAuthor(),
				local.getId(), getSecretKey(), now, true, true, true);
		wire();
		passTime(1_000);
	}

	private IdentityManager start() throws Exception {
		device = DaggerContactGroupScanTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		ContactGroupScanTestComponent.Helper.injectEagerSingletons(device);
		timeTravel = device.getTimeTravel();
		timeTravel.setCurrentTimeMillis(now);
		return device.getIdentityManager();
	}

	private void wire() throws Exception {
		clientHelper = device.getClientHelper();
		db = device.getDatabaseComponent();
		messagingManager = device.getMessagingManager();
		conversationManager = device.getConversationManager();
		validator = device.getPrivateMessageValidator();
		eventBus = device.getEventBus();
		ContactManager contactManager = device.getContactManager();
		aliceGroup = messagingManager.getContactGroup(
				contactManager.getContact(alice));
		bobGroup = messagingManager.getContactGroup(
				contactManager.getContact(bob));
	}

	private void restart() throws Exception {
		stop();
		start();
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.startServices(key);
		lifecycleManager.waitForStartup();
		wire();
	}

	private void stop() throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
	}

	@After
	public void tearDown() throws Exception {
		stop();
		deleteTestDirectory(testDir);
	}

	private MessagingManagerImpl nextRun() {
		ContactGroupScanTestComponent c = device;
		return new MessagingManagerImpl(db, clientHelper,
				c.getClientVersioningManager(), c.getMetadataParser(),
				c.getConversationManager(), c.getMessageTracker(),
				c.getContactGroupFactory(), c.getAutoDeleteManager(),
				c.getStreamingAttachmentWriter(), c.getIdentityManager(),
				validator, c.getCryptoComponent(), c.getClock());
	}

	@Test
	public void anOpenedNoteIsDeletedAtItsDeadlineWithoutTheChat()
			throws Exception {
		MessageId note = receive(aliceGroup, "SECRET:10:meet at noon");
		MessageId other = receive(aliceGroup, "an ordinary message");

		assertTrue(messagingManager.startSecretNoteCountdown(alice, note,
				SECONDS.toMillis(10)));
		passTime(SECONDS.toMillis(2));
		assertTrue(isStored(note));

		passTime(MINUTES.toMillis(1));
		assertFalse(isStored(note));
		assertTrue(isStored(other));
		assertTrue(opened().isEmpty());
	}

	@Test
	public void theDeadlineSurvivesARestartOfTheApp() throws Exception {
		MessageId note = receive(aliceGroup, "SECRET:30:meet at noon");
		assertTrue(messagingManager.startSecretNoteCountdown(alice, note,
				SECONDS.toMillis(30)));

		restart();
		assertTrue(isStored(note));
		passTime(MINUTES.toMillis(2));

		assertFalse(isStored(note));
	}

	@Test
	public void aNoteOpenedInAnEarlierRunIsDeletedBeforeTheChatShowsIt()
			throws Exception {
		MessageId note = receive(aliceGroup, "SECRET:3600:meet at noon");
		MessageId other = receive(aliceGroup, "an ordinary message");
		assertTrue(messagingManager.startSecretNoteCountdown(alice, note,
				SECONDS.toMillis(3600)));

		assertEquals(emptyList(),
				messagingManager.deleteSecretNotesOpenedEarlier(alice));
		assertTrue("the run that opened it keeps it for its countdown",
				isStored(note));

		List<Collection<MessageId>> deleted = new ArrayList<>();
		eventBus.addListener(e -> {
			if (e instanceof org.zerionproject.app.api.autodelete.event
					.ConversationMessagesDeletedEvent) {
				deleted.add(((org.zerionproject.app.api.autodelete.event
						.ConversationMessagesDeletedEvent) e).getMessageIds());
			}
		});
		assertEquals(emptyList(),
				messagingManager.getSecretNotesOpenedEarlier(alice));
		MessagingManagerImpl later = nextRun();
		assertEquals(emptyList(), later.getSecretNotesOpenedEarlier(bob));
		assertEquals(singletonList(note),
				new ArrayList<>(later.getSecretNotesOpenedEarlier(alice)));
		assertTrue("looking does not delete", isStored(note));
		assertEquals(emptyList(), later.deleteSecretNotesOpenedEarlier(bob));
		assertTrue(isStored(note));
		assertEquals(singletonList(note),
				new ArrayList<>(later.deleteSecretNotesOpenedEarlier(alice)));
		waitForEvents();

		assertFalse(isStored(note));
		assertTrue(isStored(other));
		assertTrue(opened().isEmpty());
		assertEquals(singletonList(singletonList(note)), deleted);
		for (ConversationMessageHeader h : headers(alice)) {
			assertFalse(h.getId().equals(note));
		}
		assertEquals(emptyList(), later.deleteSecretNotesOpenedEarlier(alice));
		assertEquals(emptyList(), later.getSecretNotesOpenedEarlier(alice));
	}

	@Test
	public void theStoredDeadlineFollowsTheAppClock() throws Exception {
		MessageId note = receive(aliceGroup, "SECRET:30:meet at noon");
		timeTravel.setCurrentTimeMillis(now + DAYS.toMillis(2));
		now += DAYS.toMillis(2);

		messagingManager.startSecretNoteCountdown(alice, note,
				SECONDS.toMillis(30));
		passTime(SECONDS.toMillis(20));
		assertTrue(isStored(note));
		passTime(SECONDS.toMillis(15));

		assertFalse(isStored(note));
	}

	@Test
	public void aRestartAlsoDeletesTheNoteBeforeTheChatShowsIt()
			throws Exception {
		MessageId note = receive(aliceGroup, "SECRET:3600:meet at noon");
		assertTrue(messagingManager.startSecretNoteCountdown(alice, note,
				SECONDS.toMillis(3600)));

		restart();
		assertTrue(isStored(note));
		assertEquals(singletonList(note), new ArrayList<>(
				messagingManager.deleteSecretNotesOpenedEarlier(alice)));
		assertFalse(isStored(note));
	}

	@Test
	public void deletingTheNoteClearsItsRecord() throws Exception {
		MessageId note = receive(aliceGroup, "SECRET:60:meet at noon");
		messagingManager.startSecretNoteCountdown(alice, note,
				SECONDS.toMillis(60));
		assertEquals(1, opened().size());

		assertTrue(conversationManager.deleteMessages(alice,
				singletonList(note)).allDeleted());

		assertTrue(opened().isEmpty());
	}

	@Test
	public void clearingTheChatOrRemovingTheContactClearsItsRecords()
			throws Exception {
		MessageId a = receive(aliceGroup, "SECRET:60:for alice");
		MessageId b = receive(bobGroup, "SECRET:60:for bob");
		messagingManager.startSecretNoteCountdown(alice, a,
				SECONDS.toMillis(60));
		messagingManager.startSecretNoteCountdown(bob, b,
				SECONDS.toMillis(60));
		assertEquals(2, opened().size());

		conversationManager.deleteAllMessages(alice);
		assertEquals(1, opened().size());
		assertTrue(opened().containsKey(StringUtils.toHexString(b.getBytes())));

		org.zerionproject.core.api.contact.Contact contact =
				device.getContactManager().getContact(bob);
		db.transaction(false, txn -> ((MessagingManagerImpl) messagingManager)
				.removingContact(txn, contact));
		assertTrue(opened().isEmpty());
	}

	@Test
	public void onlyAReceivedSecretNoteOfThatContactGetsACountdown()
			throws Exception {
		MessageId plain = receive(aliceGroup, "an ordinary message");
		MessageId bobs = receive(bobGroup, "SECRET:5:for bob");
		PrivateMessage sent = device.getPrivateMessageFactory()
				.createPrivateMessage(aliceGroup.getId(), nextTimestamp(),
						"SECRET:5:sent by me", emptyList());
		db.transaction(false, txn ->
				messagingManager.addLocalMessage(txn, sent));
		MessageId mine = sent.getMessage().getId();

		assertFalse(messagingManager.startSecretNoteCountdown(alice, plain,
				1_000));
		assertFalse(messagingManager.startSecretNoteCountdown(alice, bobs,
				1_000));
		assertFalse(messagingManager.startSecretNoteCountdown(alice, mine,
				1_000));
		assertFalse(messagingManager.startSecretNoteCountdown(alice,
				new MessageId(org.zerionproject.core.test.TestUtils
						.getRandomId()), 1_000));
		passTime(MINUTES.toMillis(1));

		assertTrue(isStored(plain));
		assertTrue(isStored(bobs));
		assertTrue(isStored(mine));
		assertTrue(opened().isEmpty());
	}

	@Test
	public void anEarlierDisappearingDeadlineIsKeptAndALaterOneIsShortened()
			throws Exception {
		MessageId soon = receive(aliceGroup, "SECRET:3600:soon");
		MessageId late = receive(aliceGroup, "SECRET:2:late");
		expireIn(soon, SECONDS.toMillis(10));
		expireIn(late, DAYS.toMillis(1));

		messagingManager.startSecretNoteCountdown(alice, soon,
				SECONDS.toMillis(3600));
		messagingManager.startSecretNoteCountdown(alice, late,
				SECONDS.toMillis(2));
		passTime(MINUTES.toMillis(1));

		assertFalse(isStored(soon));
		assertFalse(isStored(late));
	}

	@Test
	public void theCountdownDoesNotChangeWhatTheChatShows() throws Exception {
		MessageId note = receive(aliceGroup, "SECRET:60:meet at noon");
		ConversationMessageHeader before = header(alice, note);

		messagingManager.startSecretNoteCountdown(alice, note,
				SECONDS.toMillis(60));

		ConversationMessageHeader after = header(alice, note);
		assertEquals(before.getAutoDeleteTimer(), after.getAutoDeleteTimer());
		assertEquals(before.isRead(), after.isRead());
		assertEquals(before.isSeen(), after.isSeen());
		assertEquals(before.getTimestamp(), after.getTimestamp());
	}

	@Test
	public void theRecordsStayBounded() throws Exception {
		int max = MessagingManager.SECRET_NOTE_MAX_OPENED_RECORDS;
		List<MessageId> notes = new ArrayList<>();
		for (int i = 0; i < max + 3; i++) {
			MessageId note = receive(aliceGroup, "SECRET:3600:note " + i);
			notes.add(note);
			messagingManager.startSecretNoteCountdown(alice, note,
					SECONDS.toMillis(3600));
			passTime(1);
		}

		Settings records = opened();
		assertEquals(max, records.size());
		for (int i = 0; i < 3; i++) {
			assertFalse(records.containsKey(
					StringUtils.toHexString(notes.get(i).getBytes())));
		}
		assertTrue(records.containsKey(StringUtils.toHexString(
				notes.get(notes.size() - 1).getBytes())));
	}

	@Test
	public void theCountdownIsCappedAtTwelveHours() throws Exception {
		MessageId note = receive(aliceGroup, "SECRET:43200:note");
		messagingManager.startSecretNoteCountdown(alice, note,
				DAYS.toMillis(30));

		passTime(MessagingManager.SECRET_NOTE_MAX_COUNTDOWN_MS
				+ MINUTES.toMillis(1));

		assertFalse(isStored(note));
	}

	private Settings opened() throws Exception {
		return db.transactionWithResult(true, txn ->
				db.getSettings(txn, SECRET_NOTES_NAMESPACE));
	}

	private Collection<ConversationMessageHeader> headers(ContactId c)
			throws Exception {
		return db.transactionWithResult(true, txn ->
				messagingManager.getMessageHeaders(txn, c));
	}

	private ConversationMessageHeader header(ContactId c, MessageId m)
			throws Exception {
		for (ConversationMessageHeader h : headers(c)) {
			if (h.getId().equals(m)) return h;
		}
		throw new AssertionError("no header for the note");
	}

	private void expireIn(MessageId m, long duration) throws Exception {
		db.transaction(false, txn -> {
			db.setCleanupTimerDuration(txn, m, duration);
			db.startCleanupTimer(txn, m);
		});
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

	private MessageId receive(Group g, String text) throws Exception {
		Message m = clientHelper.createMessage(g.getId(), nextTimestamp(),
				clientHelper.toByteArray(BdfList.of(PRIVATE_MESSAGE, text,
						new BdfList())));
		BdfDictionary meta = validator.validateToBdf(m, g).getDictionary();
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, false, false));
		assertNotNull(m.getId());
		return m.getId();
	}

	private long nextTimestamp() {
		lastTimestamp = Math.max(lastTimestamp + 1, now - 60_000L);
		return lastTimestamp;
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
}

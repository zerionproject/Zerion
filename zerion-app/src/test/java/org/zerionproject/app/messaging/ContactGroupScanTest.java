package org.zerionproject.app.messaging;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.sync.validation.MessageState.INVALID;
import static org.zerionproject.core.api.sync.validation.MessageState.PENDING;
import static org.zerionproject.core.api.sync.validation.MessageState.UNKNOWN;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.zerionproject.app.api.messaging.MessagingManager.MESH_STATE_DELIVERED;
import static org.zerionproject.app.api.messaging.MessagingManager.MESH_STATE_PENDING;
import static org.zerionproject.app.api.messaging.MessagingManager.MESH_STATE_SENT;
import static org.zerionproject.app.api.messaging.MessagingManager.MSG_KEY_MESH_GROUP_PENDING;
import static org.zerionproject.app.client.MessageTrackerConstants.MSG_KEY_READ;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_INVITE_OFFER;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_ADDED;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_LEFT;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_LIST_SNAPSHOT;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_POST;
import static org.zerionproject.app.messaging.MessageTypes.LINK_PREVIEW_MESSAGE;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_REACTION;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.app.messaging.MessageTypes.VOICE_SIGNAL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_ATTACHMENT_HEADERS;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_AUTO_DELETE_TIMER;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_ADDED_NAME;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_ADDED_PUBKEY;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_CIPHERTEXT;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_EPOCH;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_RECORD_SIG;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_SENDER_PUBKEY;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GTR_INVITE_CREATOR_NAME;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GTR_INVITE_CREATOR_PUB;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GTR_INVITE_NAME;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GTR_INVITE_SALT;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_HAS_PREVIEW_IMAGE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_HAS_TEXT;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_LOCAL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MESH;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MESH_SENDER_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MESH_STATE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MSG_TYPE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_PREVIEW_DESCRIPTION;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_PREVIEW_TITLE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_PREVIEW_URL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_REACTION_EMOJI;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_REPLY_TO_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_TARGET_MESSAGE_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_TIMESTAMP;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.util.StringUtils.toHexString;

@NotNullByDefault
public class ContactGroupScanTest extends BrambleTestCase {

	private static final int POST_BYTES = 60 * 1024;
	private static final long SMALL_READ = 16 * 1024;
	private static final long ONE_POST_READ = POST_BYTES + SMALL_READ;
	private static final int BUSY_GROUP_POSTS = 30;
	private static final int QUIET_GROUP_POSTS = 8;
	private static final int LARGE_CHAT = 300;
	private static final int READS_PER_SCAN = 6;
	private static final int SNAPSHOTS = 2_000;
	private static final int SNAPSHOT_MEMBERS = 256;
	private static final int SNAPSHOT_SIGNATURE_BYTES = 4096;
	private static final int SNAPSHOTS_PER_TRANSACTION = 100;

	private final File testDir = getTestDirectory();
	private final File deviceDir = new File(testDir, "device");
	private final StoreReadRecorder reads = new StoreReadRecorder();
	private final Map<String, MessageId> named = new HashMap<>();
	private final List<MessageId> busyPosts = new ArrayList<>();
	private final byte[] meshSenderId = getRandomId();
	private final byte[] shortMeshSenderId = getRandomBytes(20);

	private ContactGroupScanTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private MessagingManagerImpl scanned;
	private WholeGroupScans before;
	private ContactId busy;
	private ContactId quiet;
	private ContactId offline;
	private GroupId busyGroup;
	private GroupId quietGroup;
	private GroupId offlineGroup;
	private long clock = 1_700_000_000_000L;

	@Before
	public void setUp() throws Exception {
		assertTrue(deviceDir.mkdirs());
		device = DaggerContactGroupScanTestComponent.builder()
				.testDatabaseConfigModule(
						new TestDatabaseConfigModule(deviceDir))
				.build();
		ContactGroupScanTestComponent.Helper.injectEagerSingletons(device);

		IdentityManager identityManager = device.getIdentityManager();
		Identity local = identityManager.createIdentity("Device");
		identityManager.registerIdentity(local);
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.startServices(getSecretKey());
		lifecycleManager.waitForStartup();

		ContactManager contactManager = device.getContactManager();
		busy = contactManager.addContact(
				identityManager.createIdentity("Busy").getLocalAuthor(),
				local.getId(), getSecretKey(), System.currentTimeMillis(),
				true, true, true);
		quiet = contactManager.addContact(
				identityManager.createIdentity("Quiet").getLocalAuthor(),
				local.getId(), getSecretKey(), System.currentTimeMillis(),
				true, true, true);
		offline = contactManager.addContact(
				identityManager.createIdentity("Offline").getLocalAuthor(),
				local.getId(), getSecretKey(), System.currentTimeMillis(),
				true, true, true);

		clientHelper = device.getClientHelper();
		db = device.getDatabaseComponent();
		scanned = new MessagingManagerImpl(
				reads.wrap(DatabaseComponent.class, db),
				reads.wrap(ClientHelper.class, clientHelper),
				device.getClientVersioningManager(),
				device.getMetadataParser(),
				device.getConversationManager(),
				reads.wrap(MessageTracker.class, device.getMessageTracker()),
				device.getContactGroupFactory(),
				device.getAutoDeleteManager(),
				device.getStreamingAttachmentWriter(),
				identityManager,
				device.getPrivateMessageValidator());
		before = new WholeGroupScans(db, clientHelper,
				device.getMessagingManager());
		busyGroup = device.getMessagingManager().getConversationId(busy);
		quietGroup = device.getMessagingManager().getConversationId(quiet);
		offlineGroup =
				device.getMessagingManager().getConversationId(offline);
		fillBusyGroup();
		fillQuietGroup();
		fillOfflineGroup();
		reads.forget();
	}

	@After
	public void tearDown() throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
		deleteTestDirectory(testDir);
	}

	@Test
	public void openingAChatReadsNoGroupPostBody() throws Exception {
		List<ConversationMessageHeader> expected = db.transactionWithResult(
				true, txn -> before.headers(txn, busy));

		Collection<ConversationMessageHeader> headers =
				db.transactionWithResult(true,
						txn -> scanned.getMessageHeaders(txn, busy));

		assertEquals("every conversation message of the fixture shows",
				11, expected.size());
		assertEquals(describe(expected), describe(headers));
		assertReadsAtMost(SMALL_READ);
	}

	@Test
	public void listingAChatsMessageIdsReadsNoGroupPostBody()
			throws Exception {
		for (ContactId c : Arrays.asList(busy, quiet, offline)) {
			Set<MessageId> expected = db.transactionWithResult(true,
					txn -> before.messageIds(txn, c));
			reads.forget();

			Set<MessageId> listed = db.transactionWithResult(true,
					txn -> scanned.getMessageIds(txn, c));

			assertEquals(c == busy ? 10 : c == quiet ? 3 : 2,
					expected.size());
			assertEquals(expected, listed);
			assertReadsAtMost(SMALL_READ);
		}
	}

	@Test
	public void readingAChatsTextsReadsNoGroupPostBody() throws Exception {
		for (ContactId c : Arrays.asList(busy, quiet, offline)) {
			Map<MessageId, String> expected = db.transactionWithResult(true,
					txn -> before.texts(txn, c));
			reads.forget();

			Map<MessageId, String> texts = db.transactionWithResult(true,
					txn -> scanned.getMessageTexts(txn, c));

			assertEquals(c == busy ? 9 : c == quiet ? 3 : 0,
					expected.size());
			assertEquals(expected, texts);
			assertReadsAtMost(SMALL_READ);
		}
	}

	@Test
	public void theRecountAfterACleanupDeletionReadsNoGroupPostBody()
			throws Exception {
		MessageId expired = named.get("remoteUnread");
		List<Integer> expected = db.transactionWithResult(false, txn -> {
			scanned.deleteMessages(txn, busyGroup, singletonList(expired));
			return before.counts(txn, busyGroup);
		});

		assertFalse(isStored(expired));
		assertEquals(Arrays.asList(10, 3), expected);
		assertEquals(expected, lastRecount(busyGroup));
		assertReadsAtMost(SMALL_READ);
	}

	@Test
	public void anExpiredGroupPostIsDeletedWithoutRecountingTheChat()
			throws Exception {
		MessageId expired = busyPosts.get(0);
		List<Integer> expected = db.transactionWithResult(false, txn -> {
			scanned.deleteMessages(txn, busyGroup, singletonList(expired));
			return before.counts(txn, busyGroup);
		});

		assertFalse(isStored(expired));
		assertEquals(Arrays.asList(11, 4), expected);
		assertTrue("a group post is not counted, so its deletion does not"
						+ " recount the chat",
				reads.callsOf("resetGroupCount").isEmpty());
		assertReadsAtMost(ONE_POST_READ);
	}

	@Test
	public void theRecountAfterDeletingFromAChatReadsNoGroupPostBody()
			throws Exception {
		MessageId deleted = named.get("quietText");
		List<Integer> expected = db.transactionWithResult(false, txn -> {
			scanned.deleteMessages(txn, quiet, singleton(deleted));
			return before.counts(txn, quietGroup);
		});

		assertFalse(isStored(deleted));
		assertEquals(Arrays.asList(2, 1), expected);
		assertEquals(expected, lastRecount(quietGroup));
		assertReadsAtMost(SMALL_READ);
	}

	@Test
	public void aMeshReplyFindsWhatItAnswersWithoutReadingGroupPostBodies()
			throws Exception {
		assertReplyAnswers(named.get("localRead").getBytes(),
				named.get("localRead"));
		assertReplyAnswers(meshSenderId, named.get("meshReceived"));
		assertReplyAnswers(shortMeshSenderId,
				named.get("meshReceivedShortId"));
		assertReplyAnswers(busyPosts.get(3).getBytes(), busyPosts.get(3));
		assertReplyAnswers(named.get("localRecordNoMetadata").getBytes(),
				null);
		assertReplyAnswers(named.get("quietText").getBytes(), null);
		assertReplyAnswers(getRandomId(), null);
		assertReplyAnswers(getRandomBytes(7), null);
		assertReadsAtMost(SMALL_READ);
	}

	@Test
	public void undeliveredMeshMessagesAreFoundWithoutReadingGroupPostBodies()
			throws Exception {
		List<String> expected = sortedDescriptions(db.transactionWithResult(
				true, before::undeliveredMeshMessages));

		List<String> found =
				sortedDescriptions(scanned.getUndeliveredMeshMessages());

		assertEquals(3, expected.size());
		assertEquals(expected, found);
		assertReadsAtMost(SMALL_READ);
	}

	@Test
	public void pendingMeshGroupRecordsAreReadOneAtATime() throws Exception {
		List<String> expected = sortedDescriptions(db.transactionWithResult(
				true, before::undeliveredMeshGroupRecords));

		List<String> found =
				sortedDescriptions(scanned.getUndeliveredMeshGroupRecords());

		assertEquals(3, expected.size());
		assertEquals(expected, found);
		assertReadsAtMost(ONE_POST_READ);
	}

	@Test
	public void sharingPendingMeshGroupRecordsReadsNoGroupPostBody()
			throws Exception {
		Set<MessageId> expected = db.transactionWithResult(true,
				before::pendingMeshGroupRecords);

		scanned.shareUndeliveredMeshGroupRecords();

		Set<MessageId> shared = new HashSet<>();
		for (Object[] args : reads.callsOf("setMessageShared")) {
			shared.add((MessageId) args[1]);
		}
		assertEquals(3, expected.size());
		assertEquals(expected, shared);
		for (MessageId m : expected) {
			assertFalse(clientHelper.getMessageMetadataAsDictionary(m)
					.getBoolean(MSG_KEY_MESH_GROUP_PENDING));
		}
		assertTrue(db.transactionWithResult(true,
				before::pendingMeshGroupRecords).isEmpty());
		assertReadsAtMost(SMALL_READ);
	}

	@Test
	public void eachScanReadsTheStoreAFixedNumberOfTimes() throws Exception {
		Map<String, Integer> few = storeReadsOfEachScan();
		addConversation(busyGroup, LARGE_CHAT);
		Map<String, Integer> many = storeReadsOfEachScan();

		String reported = "store reads of each scan " + few + ", then with "
				+ LARGE_CHAT + " more messages " + many;
		for (int n : many.values()) {
			assertTrue(reported, n <= READS_PER_SCAN);
		}
		assertEquals(reported, few, many);
	}

	@Test
	public void theScansOfALargeChatMatchTheReference() throws Exception {
		addConversation(busyGroup, LARGE_CHAT);

		List<ConversationMessageHeader> expectedHeaders =
				db.transactionWithResult(true,
						txn -> before.headers(txn, busy));
		Set<MessageId> expectedIds = db.transactionWithResult(true,
				txn -> before.messageIds(txn, busy));
		Map<MessageId, String> expectedTexts = db.transactionWithResult(
				true, txn -> before.texts(txn, busy));
		List<Integer> expectedCounts = db.transactionWithResult(true,
				txn -> before.counts(txn, busyGroup));

		assertTrue(expectedHeaders.size() > LARGE_CHAT / 3);
		assertEquals(describe(expectedHeaders), describe(
				db.transactionWithResult(true,
						txn -> scanned.getMessageHeaders(txn, busy))));
		assertEquals(expectedIds, db.transactionWithResult(true,
				txn -> scanned.getMessageIds(txn, busy)));
		assertEquals(expectedTexts, db.transactionWithResult(true,
				txn -> scanned.getMessageTexts(txn, busy)));
		reads.forget();
		db.transaction(false, txn -> scanned.deleteMessages(txn, busyGroup,
				Collections.emptyList()));
		assertTrue(reads.callsOf("resetGroupCount").isEmpty());
		MessageId counted = expectedIds.iterator().next();
		List<Integer> countsAfter = db.transactionWithResult(false, txn -> {
			scanned.deleteMessages(txn, busyGroup, singletonList(counted));
			return before.counts(txn, busyGroup);
		});
		assertEquals(expectedCounts.get(0) - 1, (int) countsAfter.get(0));
		assertEquals(countsAfter, lastRecount(busyGroup));
	}

	@Test
	public void aChatFullOfMemberListSnapshotsIsHandedOverOneAtATime()
			throws Exception {
		Group g = device.getMessagingManager().getContactGroup(
				device.getContactManager().getContact(busy));
		PrivateMessageValidator validator =
				device.getPrivateMessageValidator();
		long snapshotMetadata = 0;
		for (int i = 0; i < SNAPSHOTS; i += SNAPSHOTS_PER_TRANSACTION) {
			List<Message> messages = new ArrayList<>();
			List<BdfDictionary> metadata = new ArrayList<>();
			for (int j = 0; j < SNAPSHOTS_PER_TRANSACTION; j++) {
				Message m = clientHelper.createMessage(busyGroup, clock++,
						clientHelper.toByteArray(snapshot()));
				BdfDictionary meta =
						validator.validateToBdf(m, g).getDictionary();
				snapshotMetadata += StoreReadRecorder.sizeOf(meta);
				messages.add(m);
				metadata.add(meta);
			}
			db.transaction(false, txn -> {
				for (int j = 0; j < messages.size(); j++) {
					clientHelper.addLocalMessage(txn, messages.get(j),
							metadata.get(j), false, false);
				}
			});
		}
		assertTrue(snapshotMetadata > SNAPSHOTS * 13_000L);

		List<ConversationMessageHeader> expectedHeaders =
				db.transactionWithResult(true,
						txn -> before.headers(txn, busy));
		Set<MessageId> expectedIds = db.transactionWithResult(true,
				txn -> before.messageIds(txn, busy));
		Map<MessageId, String> expectedTexts = db.transactionWithResult(
				true, txn -> before.texts(txn, busy));
		List<Integer> expectedCounts = db.transactionWithResult(true,
				txn -> before.counts(txn, busyGroup));
		assertEquals(11, expectedHeaders.size());
		assertEquals(10, expectedIds.size());
		assertEquals(9, expectedTexts.size());
		assertEquals(Arrays.asList(11, 4), expectedCounts);

		reads.forget();
		Collection<ConversationMessageHeader> headers =
				db.transactionWithResult(true,
						txn -> scanned.getMessageHeaders(txn, busy));
		assertEquals(describe(expectedHeaders), describe(headers));
		assertHandOversAtMost(SMALL_READ, "getMessageStatus");
		reads.forget();
		assertEquals(expectedIds, db.transactionWithResult(true,
				txn -> scanned.getMessageIds(txn, busy)));
		assertHandOversAtMost(SMALL_READ);
		reads.forget();
		assertEquals(expectedTexts, db.transactionWithResult(true,
				txn -> scanned.getMessageTexts(txn, busy)));
		assertHandOversAtMost(SMALL_READ);
		reads.forget();
		db.transaction(false, txn -> scanned.deleteMessages(txn, busyGroup,
				Collections.emptyList()));
		assertTrue(reads.callsOf("resetGroupCount").isEmpty());
		MessageId counted = expectedIds.iterator().next();
		List<Integer> countsAfter = db.transactionWithResult(false, txn -> {
			scanned.deleteMessages(txn, busyGroup, singletonList(counted));
			return before.counts(txn, busyGroup);
		});
		assertEquals(expectedCounts.get(0) - 1, (int) countsAfter.get(0));
		assertEquals(countsAfter, lastRecount(busyGroup));
		assertHandOversAtMost(SMALL_READ);
	}

	private BdfList snapshot() {
		BdfList members = new BdfList();
		for (int i = 0; i < SNAPSHOT_MEMBERS; i++) {
			members.add(BdfList.of(getRandomId(), "", 0L, 0L, 0L));
		}
		return BdfList.of((long) GROUP_MEMBER_LIST_SNAPSHOT, getRandomId(),
				1L, clock, members, getRandomBytes(SNAPSHOT_SIGNATURE_BYTES));
	}

	private void assertHandOversAtMost(long bound, String... skipped) {
		long largest = reads.largestReadExcept(skipped);
		assertTrue("one read of the message store handed over " + largest
						+ " bytes (" + reads.largestReadCallExcept(skipped)
						+ ")", largest <= bound);
	}

	private Map<String, Integer> storeReadsOfEachScan() throws Exception {
		Map<String, Integer> counts = new TreeMap<>();
		reads.forget();
		db.transactionWithResult(true,
				txn -> scanned.getMessageHeaders(txn, busy));
		counts.put("opening the chat", reads.callsExcept());
		reads.forget();
		db.transactionWithResult(true,
				txn -> scanned.getMessageIds(txn, busy));
		counts.put("listing its message ids", reads.callsExcept());
		reads.forget();
		db.transactionWithResult(true,
				txn -> scanned.getMessageTexts(txn, busy));
		counts.put("reading its texts",
				reads.callsExcept("getMessageAsList"));
		reads.forget();
		db.transaction(false, txn -> scanned.deleteMessages(txn, busyGroup,
				Collections.emptyList()));
		counts.put("the recount after a cleanup deletion",
				reads.callsExcept());
		reads.forget();
		db.transactionWithResult(false, txn -> {
			Set<MessageId> ids = scanned.getMessageIds(txn, busy);
			ids.retainAll(Collections.emptySet());
			return scanned.deleteMessages(txn, busy, ids);
		});
		counts.put("a deletion by the user", reads.callsExcept());
		return counts;
	}

	private void addConversation(GroupId g, int size) throws Exception {
		byte[] mediaGroup = getRandomId();
		byte[] sender = getRandomId();
		MessageState[] states = {PENDING, INVALID, UNKNOWN};
		List<MessageId> added = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			long ts = clock++;
			boolean local = i % 2 == 0;
			boolean read = i % 3 == 0;
			switch (i % 6) {
				case 0:
					added.add(store(g, ts, BdfList.of("legacy " + i),
							meta(ts, local, read)));
					break;
				case 1:
					BdfList photo = BdfList.of(
							BdfList.of(getRandomId(), "image/jpeg"));
					added.add(store(g, ts, BdfList.of(PRIVATE_MESSAGE,
									"photo " + i, photo),
							meta(ts, local, read,
									MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE,
									MSG_KEY_HAS_TEXT, true,
									MSG_KEY_ATTACHMENT_HEADERS, photo)));
					break;
				case 2:
					added.add(store(g, ts, BdfList.of(PRIVATE_MESSAGE,
									"mesh " + i, new BdfList()),
							meta(ts, local, read, MSG_KEY_MESH, true,
									MSG_KEY_MESH_STATE, (long) (i % 3),
									MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE,
									MSG_KEY_HAS_TEXT, true,
									MSG_KEY_ATTACHMENT_HEADERS,
									new BdfList())));
					break;
				case 3:
					added.add(store(g, ts, BdfList.of(MESSAGE_REACTION,
									getRandomId(), "+1"),
							meta(ts, local, null,
									MSG_KEY_MSG_TYPE, MESSAGE_REACTION,
									MSG_KEY_TARGET_MESSAGE_ID, getRandomId(),
									MSG_KEY_REACTION_EMOJI, "+1")));
					break;
				case 4:
					added.add(store(g, ts, BdfList.of(GROUPTR_INVITE_OFFER,
							getRandomId()), inviteOffer(ts, local)));
					break;
				default:
					byte[] ciphertext = getRandomBytes(1024);
					added.add(store(g, ts, BdfList.of(GROUP_POST, mediaGroup,
									5L, sender, "Busy", ciphertext,
									getRandomBytes(64)),
							meta(ts, false, false,
									MSG_KEY_MSG_TYPE, GROUP_POST,
									MSG_KEY_GROUP_ID, mediaGroup,
									MSG_KEY_GROUP_EPOCH, 5L,
									MSG_KEY_GROUP_SENDER_PUBKEY, sender,
									MSG_KEY_GROUP_CIPHERTEXT, ciphertext)));
			}
		}
		for (int i = 0; i < added.size(); i += 17) {
			MessageId m = added.get(i);
			MessageState state = states[(i / 17) % states.length];
			db.transaction(false, txn -> db.setMessageState(txn, m, state));
		}
	}

	private void assertReplyAnswers(byte[] parent,
			@Nullable MessageId answered) throws Exception {
		MessageId expected = db.transactionWithNullableResult(true,
				txn -> before.meshParent(txn, busyGroup, parent));
		assertEquals(describe(answered), describe(expected));
		long ts = clock++;
		String text = "a reply sent at " + ts;
		byte[] replySenderId = getRandomId();
		db.transaction(false, txn -> scanned.receiveMeshMessage(txn, busy,
				text, ts, replySenderId, parent));
		MessageId reply = clientHelper.createMessage(busyGroup, ts,
				clientHelper.toByteArray(BdfList.of(text))).getId();
		byte[] replyTo = clientHelper.getMessageMetadataAsDictionary(reply)
				.getOptionalRaw(MSG_KEY_REPLY_TO_ID);
		assertEquals(describe(expected), describe(replyTo));
	}

	private void assertReadsAtMost(long bound) {
		assertTrue("one read of the message store handed over "
						+ reads.largestRead() + " bytes ("
						+ reads.largestReadCall() + ")",
				reads.largestRead() <= bound);
	}

	private List<Integer> lastRecount(GroupId g) {
		List<Object[]> recounts = reads.callsOf("resetGroupCount");
		assertEquals(1, recounts.size());
		Object[] args = recounts.get(0);
		assertEquals(g, args[1]);
		return Arrays.asList((Integer) args[2], (Integer) args[3]);
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

	private void fillBusyGroup() throws Exception {
		GroupId g = busyGroup;
		long ts = clock++;
		name("remoteUnread", store(g, ts, BdfList.of("hello"),
				meta(ts, false, false)));
		ts = clock++;
		name("localRead", store(g, ts, BdfList.of("hi there"),
				meta(ts, true, true)));
		ts = clock++;
		name("remoteRead", store(g, ts, BdfList.of("seen it"),
				meta(ts, false, true)));
		ts = clock++;
		name("meshReceived", store(g, ts, BdfList.of("over the mesh"),
				meta(ts, false, false, MSG_KEY_MESH, true,
						MSG_KEY_MESH_SENDER_ID, meshSenderId)));
		ts = clock++;
		name("meshReceivedShortId", store(g, ts, BdfList.of("short id"),
				meta(ts, false, true, MSG_KEY_MESH, true,
						MSG_KEY_MESH_SENDER_ID, shortMeshSenderId)));
		ts = clock++;
		name("meshPendingText", store(g, ts, BdfList.of("waiting"),
				meta(ts, true, true, MSG_KEY_MESH, true,
						MSG_KEY_MESH_STATE, MESH_STATE_PENDING)));
		ts = clock++;
		store(g, ts, BdfList.of(PRIVATE_MESSAGE, "a mesh reply",
						new BdfList()),
				meta(ts, true, true, MSG_KEY_MESH, true,
						MSG_KEY_MESH_STATE, MESH_STATE_SENT,
						MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE,
						MSG_KEY_HAS_TEXT, true,
						MSG_KEY_ATTACHMENT_HEADERS, new BdfList(),
						MSG_KEY_REPLY_TO_ID,
						named.get("remoteUnread").getBytes()));
		ts = clock++;
		store(g, ts, BdfList.of(PRIVATE_MESSAGE, "delivered", new BdfList()),
				meta(ts, true, true, MSG_KEY_MESH, true,
						MSG_KEY_MESH_STATE, MESH_STATE_DELIVERED,
						MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE,
						MSG_KEY_HAS_TEXT, true,
						MSG_KEY_ATTACHMENT_HEADERS, new BdfList()));
		ts = clock++;
		BdfList image = BdfList.of(BdfList.of(getRandomId(), "image/jpeg"));
		store(g, ts, BdfList.of(PRIVATE_MESSAGE, "look", image, 60_000L,
						named.get("localRead").getBytes()),
				meta(ts, false, false, MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE,
						MSG_KEY_HAS_TEXT, true,
						MSG_KEY_ATTACHMENT_HEADERS, image,
						MSG_KEY_AUTO_DELETE_TIMER, 60_000L,
						MSG_KEY_REPLY_TO_ID,
						named.get("localRead").getBytes()));
		ts = clock++;
		BdfList photo = BdfList.of(BdfList.of(getRandomId(), "image/png"));
		store(g, ts, BdfList.of(PRIVATE_MESSAGE, null, photo),
				meta(ts, true, true, MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE,
						MSG_KEY_HAS_TEXT, false,
						MSG_KEY_ATTACHMENT_HEADERS, photo));
		ts = clock++;
		store(g, ts, BdfList.of(GROUPTR_INVITE_OFFER, getRandomId()),
				inviteOffer(ts, false));
		ts = clock++;
		store(g, ts, BdfList.of(GROUPTR_INVITE_OFFER, getRandomId()),
				inviteOffer(ts, true));
		ts = clock++;
		store(g, ts, BdfList.of(MESSAGE_REACTION, getRandomId(), "+1"),
				meta(ts, false, null, MSG_KEY_MSG_TYPE, MESSAGE_REACTION,
						MSG_KEY_TARGET_MESSAGE_ID,
						named.get("remoteUnread").getBytes(),
						MSG_KEY_REACTION_EMOJI, "+1"));
		ts = clock++;
		store(g, ts, BdfList.of(VOICE_SIGNAL, 1, "call"),
				meta(ts, false, null, MSG_KEY_MSG_TYPE, VOICE_SIGNAL));
		ts = clock++;
		store(g, ts, BdfList.of(ATTACHMENT, "image/jpeg"),
				meta(ts, false, null, MSG_KEY_MSG_TYPE, ATTACHMENT,
						MSG_KEY_DESCRIPTOR_LENGTH, 16,
						MSG_KEY_CONTENT_TYPE, "image/jpeg"));
		ts = clock++;
		byte[] mediaGroup = getRandomId();
		store(g, ts, BdfList.of(GROUP_MEMBER_ADDED, mediaGroup),
				meta(ts, false, null, MSG_KEY_MSG_TYPE, GROUP_MEMBER_ADDED,
						MSG_KEY_GROUP_ID, mediaGroup,
						MSG_KEY_GROUP_ADDED_PUBKEY, getRandomId(),
						MSG_KEY_GROUP_ADDED_NAME, "Newcomer",
						MSG_KEY_GROUP_EPOCH, 6L,
						MSG_KEY_GROUP_RECORD_SIG, getRandomBytes(64),
						"groupMembershipSignedInput", getRandomBytes(200)));
		ts = clock++;
		name("localRecordNoMetadata", store(g, ts,
				BdfList.of(GROUP_MEMBER_LEFT, mediaGroup),
				new BdfDictionary()));
		byte[] sender = getRandomId();
		for (int i = 0; i < BUSY_GROUP_POSTS; i++) {
			busyPosts.add(storePost(g, mediaGroup, sender,
					i % 2 == 0 ? 60_000L : 0L));
		}
		byte[] own = getRandomId();
		storeLocalPost(g, mediaGroup, own, false);
		storeLocalPost(g, mediaGroup, own, false);
	}

	private void fillQuietGroup() throws Exception {
		GroupId g = quietGroup;
		long ts = clock++;
		name("quietText", store(g, ts, BdfList.of("quiet"),
				meta(ts, false, false)));
		ts = clock++;
		store(g, ts, BdfList.of(PRIVATE_MESSAGE, "later", new BdfList()),
				meta(ts, true, true, MSG_KEY_MESH, true,
						MSG_KEY_MESH_STATE, MESH_STATE_PENDING,
						MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE,
						MSG_KEY_HAS_TEXT, true,
						MSG_KEY_ATTACHMENT_HEADERS, new BdfList(),
						MSG_KEY_REPLY_TO_ID,
						named.get("quietText").getBytes()));
		ts = clock++;
		store(g, ts, BdfList.of(LINK_PREVIEW_MESSAGE, "see this",
						"https://example.org/", "A page", "About it"),
				meta(ts, false, false, MSG_KEY_MSG_TYPE, LINK_PREVIEW_MESSAGE,
						MSG_KEY_HAS_TEXT, true,
						MSG_KEY_PREVIEW_URL, "https://example.org/",
						MSG_KEY_PREVIEW_TITLE, "A page",
						MSG_KEY_PREVIEW_DESCRIPTION, "About it",
						MSG_KEY_HAS_PREVIEW_IMAGE, false));
		byte[] mediaGroup = getRandomId();
		byte[] sender = getRandomId();
		for (int i = 0; i < QUIET_GROUP_POSTS; i++) {
			storePost(g, mediaGroup, sender, 0L);
		}
		byte[] own = getRandomId();
		storeLocalPost(g, mediaGroup, own, true);
		storeLocalPost(g, mediaGroup, own, true);
	}

	private void fillOfflineGroup() throws Exception {
		GroupId g = offlineGroup;
		byte[] mediaGroup = getRandomId();
		long ts = clock++;
		BdfDictionary pending = new BdfDictionary();
		pending.put(MSG_KEY_MESH_GROUP_PENDING, true);
		store(g, ts, BdfList.of(GROUP_MEMBER_ADDED, mediaGroup, ts), pending);
		ts = clock++;
		BdfDictionary shared = new BdfDictionary();
		shared.put(MSG_KEY_MESH_GROUP_PENDING, false);
		store(g, ts, BdfList.of(GROUP_MEMBER_ADDED, mediaGroup, ts), shared);
	}

	private MessageId storePost(GroupId g, byte[] mediaGroup, byte[] sender,
			long timer) throws Exception {
		long ts = clock++;
		byte[] ciphertext = getRandomBytes(POST_BYTES);
		byte[] sig = getRandomBytes(64);
		BdfList body = timer == 0L
				? BdfList.of(GROUP_POST, mediaGroup, 5L, sender, "Busy",
						ciphertext, sig)
				: BdfList.of(GROUP_POST, mediaGroup, 5L, sender, "Busy",
						ciphertext, sig, timer);
		BdfDictionary meta = meta(ts, false, false,
				MSG_KEY_MSG_TYPE, GROUP_POST,
				MSG_KEY_GROUP_ID, mediaGroup,
				MSG_KEY_GROUP_EPOCH, 5L,
				MSG_KEY_GROUP_SENDER_PUBKEY, sender,
				"groupSenderName", "Busy",
				MSG_KEY_GROUP_CIPHERTEXT, ciphertext,
				MSG_KEY_GROUP_RECORD_SIG, sig);
		if (timer != 0L) meta.put(MSG_KEY_AUTO_DELETE_TIMER, timer);
		return store(g, ts, body, meta);
	}

	private void storeLocalPost(GroupId g, byte[] mediaGroup, byte[] own,
			boolean pending) throws Exception {
		long ts = clock++;
		byte[] ciphertext = getRandomBytes(POST_BYTES);
		byte[] sig = getRandomBytes(64);
		BdfDictionary meta = new BdfDictionary();
		meta.put("messageType", 32L);
		meta.put("groupId", mediaGroup);
		meta.put("groupEpoch", 5L);
		meta.put("groupSenderPubKey", own);
		meta.put("groupSenderName", "Me");
		meta.put("groupCiphertext", ciphertext);
		meta.put("timestamp", ts);
		meta.put("autoDeleteTimer", 0L);
		meta.put("groupRecordSig", sig);
		if (pending) meta.put(MSG_KEY_MESH_GROUP_PENDING, true);
		store(g, ts, BdfList.of(32L, mediaGroup, 5L, own, "Me", ciphertext,
				sig), meta);
	}

	private BdfDictionary inviteOffer(long ts, boolean local) {
		return meta(ts, local, false,
				MSG_KEY_MSG_TYPE, GROUPTR_INVITE_OFFER,
				MSG_KEY_GROUP_ID, getRandomId(),
				MSG_KEY_GTR_INVITE_NAME, "Media",
				MSG_KEY_GTR_INVITE_SALT, getRandomId(),
				MSG_KEY_GTR_INVITE_CREATOR_NAME, "Busy",
				MSG_KEY_GTR_INVITE_CREATOR_PUB, getRandomId(),
				"gtrInviteTimestamp", ts,
				MSG_KEY_GROUP_RECORD_SIG, getRandomBytes(64));
	}

	private static BdfDictionary meta(long ts, boolean local,
			@Nullable Boolean read, Object... more) {
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, ts);
		meta.put(MSG_KEY_LOCAL, local);
		if (read != null) meta.put(MSG_KEY_READ, read);
		for (int i = 0; i < more.length; i += 2) {
			meta.put((String) more[i], more[i + 1]);
		}
		return meta;
	}

	private MessageId store(GroupId g, long ts, BdfList body,
			BdfDictionary meta) throws Exception {
		Message m = clientHelper.createMessage(g, ts,
				clientHelper.toByteArray(body));
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, false, false));
		return m.getId();
	}

	private void name(String name, MessageId m) {
		named.put(name, m);
	}

	private static List<String> sortedDescriptions(Collection<?> items)
			throws Exception {
		List<String> out = new ArrayList<>();
		for (Object o : items) out.add(describe(o));
		Collections.sort(out);
		return out;
	}

	private static String describe(@Nullable Object o) throws Exception {
		if (o == null) return "null";
		if (o instanceof byte[]) {
			byte[] b = (byte[]) o;
			if (b.length <= 64) return toHexString(b);
			return b.length + ":" + toHexString(
					MessageDigest.getInstance("SHA-256").digest(b));
		}
		if (o instanceof Bytes) return describe(((Bytes) o).getBytes());
		if (o instanceof String || o instanceof Number
				|| o instanceof Boolean) {
			return String.valueOf(o);
		}
		if (o instanceof Collection) {
			StringBuilder s = new StringBuilder("[");
			for (Object e : (Collection<?>) o) {
				s.append(describe(e)).append(", ");
			}
			return s.append("]").toString();
		}
		Map<String, String> parts = new TreeMap<>();
		for (Field f : o.getClass().getFields()) {
			if (!Modifier.isStatic(f.getModifiers())) {
				parts.put(f.getName(), describe(f.get(o)));
			}
		}
		for (Method m : o.getClass().getMethods()) {
			String n = m.getName();
			if (m.getParameterCount() != 0
					|| Modifier.isStatic(m.getModifiers())
					|| m.getDeclaringClass() == Object.class) {
				continue;
			}
			if (n.startsWith("get") || n.startsWith("is")
					|| n.startsWith("has")) {
				parts.put(n, describe(m.invoke(o)));
			}
		}
		return o.getClass().getSimpleName() + parts;
	}
}

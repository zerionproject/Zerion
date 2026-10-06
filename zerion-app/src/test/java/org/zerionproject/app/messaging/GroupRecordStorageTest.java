package org.zerionproject.app.messaging;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.zerionproject.core.system.TimeTravelModule;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.core.test.TimeTravel;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupPostReceivedEvent;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_LIST_SNAPSHOT;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_POST;
import static org.zerionproject.core.api.cleanup.CleanupManager.BATCH_DELAY_MS;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class GroupRecordStorageTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();

	private ContactGroupScanTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private MessagingManager messagingManager;
	private PrivateMessageValidator validator;
	private CryptoComponent crypto;
	private TimeTravel timeTravel;
	private EventBus eventBus;
	private Group contactGroup;
	private long now;
	private final List<GroupPostReceivedEvent> posts =
			new CopyOnWriteArrayList<>();

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
		ContactId contact = contactManager.addContact(
				identityManager.createIdentity("Contact").getLocalAuthor(),
				local.getId(), getSecretKey(), now, true, true, true);
		clientHelper = device.getClientHelper();
		db = device.getDatabaseComponent();
		messagingManager = device.getMessagingManager();
		validator = device.getPrivateMessageValidator();
		crypto = device.getCryptoComponent();
		eventBus = device.getEventBus();
		contactGroup = messagingManager.getContactGroup(
				contactManager.getContact(contact));
		eventBus.addListener(e -> {
			if (e instanceof GroupPostReceivedEvent) {
				posts.add((GroupPostReceivedEvent) e);
			}
		});
	}

	@After
	public void tearDown() throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
		deleteTestDirectory(testDir);
	}

	@Test
	public void aPostLargerThan64KiBIsTakenInWhole() throws Exception {
		byte[] body = getRandomBytes(300 * 1024);

		MessageId id = deliver(groupPost(body));
		waitForEvents();

		assertEquals(1, posts.size());
		assertArrayEquals(body, posts.get(0).getCiphertext());
		BdfDictionary meta = db.transactionWithResult(true, txn ->
				clientHelper.getMessageMetadataAsDictionary(txn, id));
		assertNull("the body was copied into the metadata",
				meta.get("groupCiphertext"));
		assertEquals((long) body.length,
				(long) meta.getLong("groupBodyLength"));
	}

	@Test
	public void aMemberListSnapshotIsKeptOnlyBriefly() throws Exception {
		BdfList members = new BdfList();
		for (int i = 0; i < 64; i++) {
			members.add(BdfList.of(getRandomId(), "", 0L, 0L, 0L));
		}
		MessageId id = deliver(BdfList.of(
				(long) GROUP_MEMBER_LIST_SNAPSHOT, getRandomId(), 1L, now,
				members, getRandomBytes(
						PostQuantumConstants.HYBRID_SIGNATURE_BYTES)));
		assertTrue(isStored(id));

		passTime(MINUTES.toMillis(5) + BATCH_DELAY_MS + 1_000);

		assertFalse("a snapshot record is kept for ever", isStored(id));
	}

	private BdfList groupPost(byte[] body) throws Exception {
		KeyPair keys = crypto.generateSignatureKeyPair();
		byte[] senderPub = keys.getPublic().getEncoded();
		byte[] groupId = getRandomId();
		long timestamp = now - 1;
		byte[] nameHash = crypto.hash("org.zerionproject/GROUP_POST_NAME",
				"Name".getBytes(StandardCharsets.UTF_8));
		byte[] ctHash = crypto.hash("org.zerionproject/GROUP_POST_CT", body);
		byte[] signed = new byte[32 + 4 + 32 + 32 + 32 + 8 + 8];
		System.arraycopy(groupId, 0, signed, 0, 32);
		signed[35] = 1;
		System.arraycopy(senderPub, 0, signed, 36, 32);
		System.arraycopy(nameHash, 0, signed, 68, 32);
		System.arraycopy(ctHash, 0, signed, 100, 32);
		for (int i = 0; i < 8; i++) {
			signed[132 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		byte[] ed = crypto.sign("org.zerionproject/GROUP_POST", signed,
				keys.getPrivate());
		byte[] sig = new byte[PostQuantumConstants.HYBRID_SIGNATURE_BYTES];
		System.arraycopy(ed, 0, sig, 0, ed.length);
		postTimestamp = timestamp;
		return BdfList.of((long) GROUP_POST, groupId, 1L, senderPub, "Name",
				body, sig);
	}

	private long postTimestamp = 0;

	private MessageId deliver(BdfList record) throws Exception {
		long ts = postTimestamp != 0 ? postTimestamp : now - 1;
		Message m = clientHelper.createMessage(contactGroup.getId(), ts,
				clientHelper.toByteArray(record));
		BdfDictionary meta = validator.validateToBdf(m, contactGroup)
				.getDictionary();
		db.transaction(false, txn -> {
			clientHelper.addLocalMessage(txn, m, meta, false, false);
			Metadata raw = db.getMessageMetadata(txn, m.getId());
			((IncomingMessageHook) messagingManager).incomingMessage(txn, m,
					raw);
		});
		return m.getId();
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

	private void passTime(long millis) throws Exception {
		waitForEvents();
		now += millis;
		timeTravel.setCurrentTimeMillis(now);
		waitForEvents();
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
}

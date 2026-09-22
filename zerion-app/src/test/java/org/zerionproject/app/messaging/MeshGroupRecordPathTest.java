package org.zerionproject.app.messaging;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.PrivateMessageReceivedEvent;
import org.zerionproject.app.api.messaging.event.TypingIndicatorReceivedEvent;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_INVITE_DECLINE;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_POST;
import static org.zerionproject.app.messaging.MessageTypes.MESH_PREKEY_BUNDLE;
import static org.zerionproject.app.messaging.MessageTypes.TYPING_INDICATOR;
import static org.zerionproject.app.messaging.MessageTypes.VOICE_SIGNAL;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * PROTO-15: the mesh group-record path accepts group records only. A
 * private-message type that has its own online ordering and freshness
 * rules (a typing indicator, a call signal, a prekey bundle, a legacy text
 * with no type) is refused before it is stored or dispatched.
 */
@NotNullByDefault
public class MeshGroupRecordPathTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();
	private final File deviceDir = new File(testDir, "device");
	private final SecretKey rootKey = getSecretKey();

	private AttachmentResourceTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private EventBus eventBus;
	private MessagingManager messagingManager;
	private ContactId contactId;
	private GroupId groupId;

	@Before
	public void setUp() throws Exception {
		assertTrue(deviceDir.mkdirs());
		device = DaggerAttachmentResourceTestComponent.builder()
				.testDatabaseConfigModule(
						new TestDatabaseConfigModule(deviceDir))
				.build();
		AttachmentResourceTestComponent.Helper.injectEagerSingletons(device);

		IdentityManager identityManager = device.getIdentityManager();
		Identity local = identityManager.createIdentity("Device");
		identityManager.registerIdentity(local);

		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.startServices(getSecretKey());
		lifecycleManager.waitForStartup();

		ContactManager contactManager = device.getContactManager();
		Author remote = identityManager.createIdentity("Contact")
				.getLocalAuthor();
		contactId = contactManager.addContact(remote, local.getId(), rootKey,
				System.currentTimeMillis(), true, true, true);

		clientHelper = device.getClientHelper();
		db = device.getDatabaseComponent();
		eventBus = device.getEventBus();
		messagingManager = device.getMessagingManager();
		groupId = messagingManager.getConversationId(contactId);
	}

	@After
	public void tearDown() throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
		deleteTestDirectory(testDir);
	}

	@Test
	public void typeGateAdmitsGroupRecordsOnly() {
		for (int t = GROUP_POST; t <= GROUPTR_INVITE_DECLINE; t++) {
			assertTrue(MessagingManagerImpl.isMeshGroupRecordType(t));
		}
		assertFalse(MessagingManagerImpl.isMeshGroupRecordType(null));
		assertFalse(MessagingManagerImpl.isMeshGroupRecordType(0));
		assertFalse(MessagingManagerImpl.isMeshGroupRecordType(VOICE_SIGNAL));
		assertFalse(MessagingManagerImpl.isMeshGroupRecordType(
				TYPING_INDICATOR));
		assertFalse(MessagingManagerImpl.isMeshGroupRecordType(
				MESH_PREKEY_BUNDLE));
		assertFalse(MessagingManagerImpl.isMeshGroupRecordType(
				GROUP_POST - 1));
		assertFalse(MessagingManagerImpl.isMeshGroupRecordType(
				GROUPTR_INVITE_DECLINE + 1));
	}

	@Test
	public void typingIndicatorViaMeshGroupPathIsNeitherStoredNorDispatched()
			throws Exception {
		Counter typing = new Counter(TypingIndicatorReceivedEvent.class);
		eventBus.addListener(typing);
		long ts = System.currentTimeMillis();
		byte[] record = clientHelper.toByteArray(
				BdfList.of(TYPING_INDICATOR, true));
		messagingManager.receiveMeshGroupRecord(contactId, record, ts);
		Thread.sleep(200);
		eventBus.removeListener(typing);
		assertEquals(0, typing.count.get());
		assertFalse(messageExists(idOf(ts, record)));
	}

	@Test
	public void legacyTextViaMeshGroupPathIsNeitherStoredNorDispatched()
			throws Exception {
		Counter received = new Counter(PrivateMessageReceivedEvent.class);
		eventBus.addListener(received);
		long ts = System.currentTimeMillis();
		byte[] record = clientHelper.toByteArray(BdfList.of("hello"));
		messagingManager.receiveMeshGroupRecord(contactId, record, ts);
		Thread.sleep(200);
		eventBus.removeListener(received);
		assertEquals(0, received.count.get());
		assertFalse(messageExists(idOf(ts, record)));
	}

	@Test
	public void groupRecordViaMeshGroupPathIsStored() throws Exception {
		long ts = System.currentTimeMillis();
		byte[] record = clientHelper.toByteArray(BdfList.of(
				GROUPTR_INVITE_DECLINE, getRandomBytes(32), ts,
				getRandomBytes(64)));
		messagingManager.receiveMeshGroupRecord(contactId, record, ts);
		assertTrue(messageExists(idOf(ts, record)));
	}

	private MessageId idOf(long ts, byte[] record) throws Exception {
		Message m = clientHelper.createMessage(groupId, ts, record);
		return m.getId();
	}

	private boolean messageExists(MessageId id) throws Exception {
		return db.transactionWithResult(true, txn -> {
			try {
				clientHelper.getMessage(txn, id);
				return true;
			} catch (NoSuchMessageException e) {
				return false;
			}
		});
	}

	@NotNullByDefault
	private static class Counter implements EventListener {
		private final Class<?> type;
		final AtomicInteger count = new AtomicInteger();

		Counter(Class<?> type) {
			this.type = type;
		}

		@Override
		public void eventOccurred(Event e) {
			if (type.isInstance(e)) count.incrementAndGet();
		}
	}
}

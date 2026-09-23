package org.zerionproject.app.messaging;

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
import org.zerionproject.app.api.messaging.event.AttachmentReceivedEvent;
import org.zerionproject.app.api.messaging.event.TypingIndicatorReceivedEvent;

import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT_CHUNK;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT_MANIFEST;
import static org.zerionproject.app.messaging.MessageTypes.TYPING_INDICATOR;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MANIFEST_ID;
import static org.zerionproject.core.api.db.DatabaseComponent.NO_CLEANUP_DEADLINE;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Real-database delivery tests for the receive-side attachment and ephemeral
 * resource bounds (PROTO-05, PROTO-06, PROTO-11). Messages are injected as if
 * received from a contact so the full validation and delivery pipeline runs.
 */
@NotNullByDefault
public class AttachmentResourceIntegrationTest extends BrambleTestCase {

	private static final long TIMEOUT_MS = 15_000;
	private static final int CHUNK_DATA = 100;

	private final File testDir = getTestDirectory();
	private final File deviceDir = new File(testDir, "device");
	private final SecretKey rootKey = getSecretKey();

	private AttachmentResourceTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private EventBus eventBus;
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
		groupId = device.getMessagingManager().getConversationId(contactId);
		final ContactId c = contactId;
		final GroupId g = groupId;
		db.transaction(false, txn -> db.setGroupVisibility(txn, c, g,
				org.zerionproject.core.api.sync.Group.Visibility.SHARED));
	}

	@After
	public void tearDown() throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
		deleteTestDirectory(testDir);
	}

	@Test
	public void oversizedChunksAreDeletedOnManifestDelivery() throws Exception {
		List<MessageId> chunkIds = deliverChunks(2, CHUNK_DATA);
		Message manifest = manifest("image/jpeg", 1, chunkIds);
		AttachmentLatch latch = new AttachmentLatch(manifest.getId());
		eventBus.addListener(latch);
		deliver(manifest);
		latch.await();
		eventBus.removeListener(latch);

		for (MessageId c : chunkIds) {
			assertFalse("oversized chunk must not be retained",
					messageExists(c));
		}
	}

	@Test
	public void matchingChunksAreRetainedOnManifestDelivery() throws Exception {
		List<MessageId> chunkIds = deliverChunks(2, CHUNK_DATA);
		Message manifest =
				manifest("image/jpeg", 2L * CHUNK_DATA, chunkIds);
		AttachmentLatch latch = new AttachmentLatch(manifest.getId());
		eventBus.addListener(latch);
		deliver(manifest);
		latch.await();
		eventBus.removeListener(latch);

		for (MessageId c : chunkIds) {
			assertTrue("legitimate chunk must be retained", messageExists(c));
		}
	}

	@Test
	public void ownedChunksAreStampedWithTheirManifestId() throws Exception {
		List<MessageId> chunkIds = deliverChunks(2, CHUNK_DATA);
		Message manifest =
				manifest("image/jpeg", 2L * CHUNK_DATA, chunkIds);
		AttachmentLatch manifestLatch = new AttachmentLatch(manifest.getId());
		eventBus.addListener(manifestLatch);
		deliver(manifest);
		manifestLatch.await();
		eventBus.removeListener(manifestLatch);

		BdfList headers = new BdfList();
		headers.add(BdfList.of(manifest.getId().getBytes(), "image/jpeg"));
		Message pm = create(BdfList.of(0, null, headers));
		PrivateLatch privateLatch = new PrivateLatch();
		eventBus.addListener(privateLatch);
		deliver(pm);
		privateLatch.await();
		eventBus.removeListener(privateLatch);

		byte[] manifestId = manifest.getId().getBytes();
		for (MessageId c : chunkIds) {
			assertArrayEquals("chunk must be stamped with its manifest id",
					manifestId, readManifestStamp(c));
		}
	}

	private byte[] readManifestStamp(MessageId c) throws Exception {
		return db.transactionWithNullableResult(true, txn -> {
			try {
				return clientHelper.getMessageMetadataAsDictionary(txn, c)
						.getOptionalRaw(MSG_KEY_MANIFEST_ID);
			} catch (org.zerionproject.core.api.FormatException e) {
				throw new org.zerionproject.core.api.db.DbException(e);
			}
		});
	}

	@Test
	public void chunkDeliveryDoesNotScanForOwnership() throws Exception {
		List<MessageId> chunkIds = deliverChunks(1, CHUNK_DATA);
		assertEquals(1, chunkIds.size());
		long deadline = db.transactionWithResult(true,
				db::getNextCleanupDeadline);
		assertTrue("an unreferenced chunk must be given a cleanup timer",
				deadline != NO_CLEANUP_DEADLINE);
	}

	@Test
	public void remoteTypingIndicatorGetsACleanupTimer() throws Exception {
		Message typing = create(BdfList.of(TYPING_INDICATOR, true));
		TypingLatch latch = new TypingLatch();
		eventBus.addListener(latch);
		deliver(typing);
		latch.await();
		eventBus.removeListener(latch);

		long deadline = db.transactionWithResult(true,
				db::getNextCleanupDeadline);
		assertTrue("a remote typing indicator must self-expire",
				deadline != NO_CLEANUP_DEADLINE);
	}

	private List<MessageId> deliverChunks(int count, int dataLength)
			throws Exception {
		List<MessageId> ids = new ArrayList<>(count);
		List<AttachmentLatch> latches = new ArrayList<>(count);
		List<Message> chunks = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			Message chunk = chunk(i, dataLength);
			chunks.add(chunk);
			ids.add(chunk.getId());
			AttachmentLatch latch = new AttachmentLatch(chunk.getId());
			latches.add(latch);
			eventBus.addListener(latch);
		}
		for (Message chunk : chunks) deliver(chunk);
		for (AttachmentLatch latch : latches) {
			latch.await();
			eventBus.removeListener(latch);
		}
		return ids;
	}

	private Message chunk(int index, int dataLength) throws Exception {
		byte[] header = clientHelper.toByteArray(
				BdfList.of(ATTACHMENT_CHUNK, index, dataLength));
		byte[] body = new byte[header.length + dataLength];
		System.arraycopy(header, 0, body, 0, header.length);
		byte[] data = getRandomBytes(dataLength);
		System.arraycopy(data, 0, body, header.length, dataLength);
		return clientHelper.createMessage(groupId,
				System.currentTimeMillis() + index, body);
	}

	private Message manifest(String contentType, long totalSize,
			List<MessageId> chunkIds) throws Exception {
		BdfList chunkIdList = new BdfList();
		for (MessageId c : chunkIds) chunkIdList.add(c.getBytes());
		return create(BdfList.of(ATTACHMENT_MANIFEST, contentType, totalSize,
				chunkIds.size(), getRandomBytes(32), chunkIdList));
	}

	private Message create(BdfList body) throws Exception {
		return clientHelper.createMessage(groupId,
				System.currentTimeMillis(), clientHelper.toByteArray(body));
	}

	private void deliver(Message m) throws Exception {
		db.transaction(false, txn -> db.receiveMessage(txn, contactId, m));
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
	private static class AttachmentLatch implements EventListener {
		private final MessageId target;
		private final CountDownLatch latch = new CountDownLatch(1);

		AttachmentLatch(MessageId target) {
			this.target = target;
		}

		@Override
		public void eventOccurred(Event e) {
			if (e instanceof AttachmentReceivedEvent
					&& ((AttachmentReceivedEvent) e).getMessageId()
					.equals(target)) {
				latch.countDown();
			}
		}

		void await() throws InterruptedException {
			assertTrue("timed out waiting for attachment delivery",
					latch.await(TIMEOUT_MS, MILLISECONDS));
		}
	}

	@NotNullByDefault
	private static class PrivateLatch implements EventListener {
		private final CountDownLatch latch = new CountDownLatch(1);

		@Override
		public void eventOccurred(Event e) {
			if (e instanceof org.zerionproject.app.api.messaging.event
					.PrivateMessageReceivedEvent) {
				latch.countDown();
			}
		}

		void await() throws InterruptedException {
			assertTrue("timed out waiting for private message delivery",
					latch.await(TIMEOUT_MS, MILLISECONDS));
		}
	}

	@NotNullByDefault
	private static class TypingLatch implements EventListener {
		private final CountDownLatch latch = new CountDownLatch(1);

		@Override
		public void eventOccurred(Event e) {
			if (e instanceof TypingIndicatorReceivedEvent) latch.countDown();
		}

		void await() throws InterruptedException {
			assertTrue("timed out waiting for typing indicator delivery",
					latch.await(TIMEOUT_MS, MILLISECONDS));
		}
	}
}

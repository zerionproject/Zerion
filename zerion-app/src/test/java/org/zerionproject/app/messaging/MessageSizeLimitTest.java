package org.zerionproject.app.messaging;

import org.zerionproject.app.api.attachment.FileTooBigException;
import org.zerionproject.app.api.messaging.MessageTooLargeException;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.api.attachment.MediaConstants.MAX_DELIVERABLE_IMAGE_SIZE;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_DELIVERABLE_MESSAGE_BODY_LENGTH;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.util.StringUtils.getRandomString;

@NotNullByDefault
public class MessageSizeLimitTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();
	private final List<MessageId> marked = new ArrayList<>();

	private ContactGroupScanTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private MessagingManagerImpl sender;
	private Group contactGroup;
	private long timestamp = System.currentTimeMillis() - 60_000L;

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
		ContactId contact = contactManager.addContact(
				identityManager.createIdentity("Contact").getLocalAuthor(),
				local.getId(), getSecretKey(), System.currentTimeMillis(),
				true, true, true);
		clientHelper = device.getClientHelper();
		db = device.getDatabaseComponent();
		MessagingManager messagingManager = device.getMessagingManager();
		contactGroup = messagingManager.getContactGroup(
				contactManager.getContact(contact));
		sender = new MessagingManagerImpl(db, clientHelper,
				device.getClientVersioningManager(),
				device.getMetadataParser(), device.getConversationManager(),
				device.getMessageTracker(), device.getContactGroupFactory(),
				device.getAutoDeleteManager(),
				device.getStreamingAttachmentWriter(),
				device.getIdentityManager(), device.getPrivateMessageValidator(),
				device.getCryptoComponent(), marked::add);
	}

	@After
	public void tearDown() throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
		deleteTestDirectory(testDir);
	}

	@Test
	public void aPrivateMessageTooLargeForOneSyncRecordIsNotStored()
			throws Exception {
		PrivateMessage huge = privateMessage(
				MAX_DELIVERABLE_MESSAGE_BODY_LENGTH + 1);
		int before = storedMessages();

		try {
			sender.addLocalMessage(huge);
			fail();
		} catch (MessageTooLargeException expected) {
		}

		assertEquals(before, storedMessages());
		assertTrue(marked.isEmpty());
	}

	@Test
	public void aPrivateMessageThatFitsOneSyncRecordIsStored()
			throws Exception {
		PrivateMessage fits = privateMessage(
				MAX_DELIVERABLE_MESSAGE_BODY_LENGTH);
		int before = storedMessages();

		sender.addLocalMessage(fits);

		assertEquals(before + 1, storedMessages());
	}

	@Test
	public void anImageTooLargeForOneSyncRecordIsNotStored() throws Exception {
		int before = storedMessages();

		try {
			sender.addLocalAttachment(contactGroup.getId(), ++timestamp,
					"image/jpeg", new ByteArrayInputStream(
							new byte[MAX_DELIVERABLE_MESSAGE_BODY_LENGTH]));
			fail();
		} catch (FileTooBigException expected) {
		}

		assertEquals(before, storedMessages());
	}

	@Test
	public void anImageAtTheLimitIsStored() throws Exception {
		int before = storedMessages();

		sender.addLocalAttachment(contactGroup.getId(), ++timestamp,
				"image/jpeg", new ByteArrayInputStream(
						new byte[MAX_DELIVERABLE_IMAGE_SIZE]));

		assertEquals(before + 1, storedMessages());
	}

	private PrivateMessage privateMessage(int bodyLength) throws Exception {
		String text = "";
		int length = clientHelper.toByteArray(BdfList.of(PRIVATE_MESSAGE,
				text, new BdfList())).length;
		int textLength = bodyLength - length;
		for (int i = 0; i < 4; i++) {
			text = getRandomString(textLength);
			length = clientHelper.toByteArray(BdfList.of(PRIVATE_MESSAGE,
					text, new BdfList())).length;
			textLength += bodyLength - length;
		}
		Message m = clientHelper.createMessage(contactGroup.getId(),
				++timestamp, BdfList.of(PRIVATE_MESSAGE, text, new BdfList()));
		assertEquals(bodyLength, m.getBody().length);
		return new PrivateMessage(m);
	}

	private int storedMessages() throws Exception {
		return db.transactionWithResult(true, txn ->
				db.getMessageIds(txn, contactGroup.getId()).size());
	}
}

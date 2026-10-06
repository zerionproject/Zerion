package org.zerionproject.app.messaging;

import org.zerionproject.core.api.cleanup.CleanupHook;
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
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.system.TimeTravelModule;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.client.MessageTracker.GroupCount;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.VoiceSignal;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_INVITE_OFFER;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.app.messaging.MessageTypes.VOICE_SIGNAL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_ATTACHMENT_HEADERS;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_HAS_TEXT;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_LOCAL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MSG_TYPE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_TIMESTAMP;
import static org.zerionproject.app.client.MessageTrackerConstants.MSG_KEY_READ;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class ConversationCountDriftTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();

	private ContactGroupScanTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private MessagingManager messagingManager;
	private MessageTracker messageTracker;
	private PrivateMessageValidator validator;
	private ContactId contact;
	private Group group;
	private long now;

	@Before
	public void setUp() throws Exception {
		File dir = new File(testDir, "device");
		assertTrue(dir.mkdirs());
		device = DaggerContactGroupScanTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		ContactGroupScanTestComponent.Helper.injectEagerSingletons(device);
		now = System.currentTimeMillis();
		device.getTimeTravel().setCurrentTimeMillis(now);
		IdentityManager identityManager = device.getIdentityManager();
		Identity local = identityManager.createIdentity("Device");
		identityManager.registerIdentity(local);
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.startServices(getSecretKey());
		lifecycleManager.waitForStartup();
		ContactManager contactManager = device.getContactManager();
		contact = contactManager.addContact(
				identityManager.createIdentity("Contact").getLocalAuthor(),
				local.getId(), getSecretKey(), now, true, true, true);
		clientHelper = device.getClientHelper();
		db = device.getDatabaseComponent();
		messagingManager = device.getMessagingManager();
		messageTracker = device.getMessageTracker();
		validator = device.getPrivateMessageValidator();
		group = messagingManager.getContactGroup(
				contactManager.getContact(contact));
	}

	@After
	public void tearDown() throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
		deleteTestDirectory(testDir);
	}

	@Test
	public void aSentVoiceSignalIsNotCounted() throws Exception {
		receiveText();
		GroupCount before = count();

		Message m = clientHelper.createMessage(group.getId(), now + 1,
				clientHelper.toByteArray(BdfList.of(VOICE_SIGNAL, 1, "call",
						"key material")));
		messagingManager.addLocalVoiceSignal(new VoiceSignal(m,
				VoiceSignalType.fromValue(1), "call", "key material"));

		assertEquals("a sent voice signal, which the chat does not show, was"
				+ " counted", before.getMsgCount(), count().getMsgCount());
	}

	@Test
	public void aReceivedInviteOfferIsCountedAndKeptByARecount()
			throws Exception {
		MessageId text = receiveText();
		receive(BdfList.of(GROUPTR_INVITE_OFFER, getRandomId(), "Group",
				getRandomBytes(32), "Creator", getRandomBytes(32), now,
				getRandomBytes(64)));
		assertEquals(2, count().getMsgCount());

		db.transaction(false, txn -> ((CleanupHook) messagingManager)
				.deleteMessages(txn, group.getId(), singletonList(text)));

		assertEquals("the recount after a deletion dropped the invite card"
				+ " the chat still shows", 1, count().getMsgCount());
		assertEquals(1, count().getUnreadCount());
	}

	@Test
	public void attachmentsOfAMessageWithUnreadableMetadataAreRemoved()
			throws Exception {
		MessageId attachment = receiveAttachment();
		Message m = clientHelper.createMessage(group.getId(), now + 5,
				clientHelper.toByteArray(BdfList.of(PRIVATE_MESSAGE, "text",
						new BdfList())));
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, now + 5);
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_READ, false);
		meta.put(MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE);
		meta.put(MSG_KEY_HAS_TEXT, true);
		meta.put(MSG_KEY_ATTACHMENT_HEADERS, BdfList.of(
				BdfList.of(attachment.getBytes(), "image/jpeg"), "broken"));
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, false, false));

		db.transaction(false, txn -> ((CleanupHook) messagingManager)
				.deleteMessages(txn, group.getId(),
						singletonList(m.getId())));

		assertFalse(isStored(m.getId()));
		assertFalse("the attachment of a message whose metadata could not"
				+ " be read stays stored", isStored(attachment));
	}

	private GroupCount count() throws Exception {
		return messageTracker.getGroupCount(group.getId());
	}

	private MessageId receiveText() throws Exception {
		return receive(BdfList.of(PRIVATE_MESSAGE, "text " + now,
				new BdfList()));
	}

	private MessageId receiveAttachment() throws Exception {
		byte[] descriptor = clientHelper.toByteArray(
				BdfList.of(ATTACHMENT, "image/jpeg"));
		byte[] data = getRandomBytes(1000);
		byte[] body = new byte[descriptor.length + data.length];
		System.arraycopy(descriptor, 0, body, 0, descriptor.length);
		System.arraycopy(data, 0, body, descriptor.length, data.length);
		return receive(body);
	}

	private MessageId receive(BdfList body) throws Exception {
		return receive(clientHelper.toByteArray(body));
	}

	private MessageId receive(byte[] body) throws Exception {
		Message m = clientHelper.createMessage(group.getId(), ++now, body);
		BdfDictionary meta = validator.validateToBdf(m, group).getDictionary();
		db.transaction(false, txn -> {
			clientHelper.addLocalMessage(txn, m, meta, false, false);
			dispatch(txn, m, meta);
		});
		return m.getId();
	}

	private void dispatch(org.zerionproject.core.api.db.Transaction txn,
			Message m, BdfDictionary meta) throws Exception {
		Integer type = meta.getOptionalInt(MSG_KEY_MSG_TYPE);
		if (type != null && type == ATTACHMENT) return;
		org.zerionproject.core.api.sync.validation.IncomingMessageHook hook =
				(org.zerionproject.core.api.sync.validation
						.IncomingMessageHook) messagingManager;
		hook.incomingMessage(txn, m, db.getMessageMetadata(txn, m.getId()));
	}

	private boolean isStored(MessageId id) throws Exception {
		return db.transactionWithResult(true, txn -> {
			try {
				db.getMessage(txn, id);
				return true;
			} catch (NoSuchMessageException e) {
				return false;
			}
		});
	}
}

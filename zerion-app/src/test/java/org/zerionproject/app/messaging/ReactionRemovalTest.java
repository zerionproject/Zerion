package org.zerionproject.app.messaging;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_REACTION;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class ReactionRemovalTest extends BrambleTestCase {

	private static final String HEART = "heart";
	private static final int REMOVAL_TYPE = 11;

	private final File testDir = getTestDirectory();

	private ContactGroupScanTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private MessagingManager messagingManager;
	private PrivateMessageValidator validator;
	private ContactId contact;
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
		contact = contactManager.addContact(
				identityManager.createIdentity("Contact").getLocalAuthor(),
				local.getId(), getSecretKey(), System.currentTimeMillis(),
				true, true, true);
		clientHelper = device.getClientHelper();
		db = device.getDatabaseComponent();
		messagingManager = device.getMessagingManager();
		validator = device.getPrivateMessageValidator();
		contactGroup = messagingManager.getContactGroup(
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
	public void aContactsRemovedReactionIsNoLongerShown() throws Exception {
		MessageId target = deliver(BdfList.of(PRIVATE_MESSAGE, "hi",
				new BdfList()));
		deliver(BdfList.of(MESSAGE_REACTION, target.getBytes(), HEART));
		assertEquals(1, reactionsOn(target));

		deliver(BdfList.of(REMOVAL_TYPE, target.getBytes(), HEART));

		assertEquals("the contact removed its reaction but it still shows",
				0, reactionsOn(target));
	}

	@Test
	public void aReactionArrivingAfterItsRemovalIsNotShown()
			throws Exception {
		MessageId target = deliver(BdfList.of(PRIVATE_MESSAGE, "hi",
				new BdfList()));
		long reacted = timestamp + 1;
		deliverAt(BdfList.of(REMOVAL_TYPE, target.getBytes(), HEART),
				reacted + 10);
		deliverAt(BdfList.of(MESSAGE_REACTION, target.getBytes(), HEART),
				reacted);

		assertEquals(0, reactionsOn(target));
	}

	@Test
	public void aRemovalIsSentToAContactThatCanTakeItIn() throws Exception {
		MessagingManagerImpl sender = senderSeeing(8);
		MessageId target = deliver(BdfList.of(PRIVATE_MESSAGE, "hi",
				new BdfList()));
		assertTrue(sender.addLocalReaction(contact, target, HEART));

		assertTrue(sender.addLocalReaction(contact, target, HEART));

		assertEquals(1, removalsSent());
		assertEquals(0, reactionsOn(target));
	}

	@Test
	public void theSenderIsToldWhenTheContactCannotBeToldOfARemoval()
			throws Exception {
		MessagingManagerImpl sender = senderSeeing(7);
		MessageId target = deliver(BdfList.of(PRIVATE_MESSAGE, "hi",
				new BdfList()));
		sender.addLocalReaction(contact, target, HEART);

		assertFalse(sender.addLocalReaction(contact, target, HEART));

		assertEquals(0, removalsSent());
		assertEquals(0, reactionsOn(target));
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
				device.getIdentityManager(), validator);
	}

	private int removalsSent() throws Exception {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry("messageType", REMOVAL_TYPE),
				new BdfEntry("local", true));
		return db.transactionWithResult(true, txn -> clientHelper
				.getMessageIds(txn, contactGroup.getId(), query).size());
	}

	private int reactionsOn(MessageId target) throws Exception {
		Map<String, Integer> r = messagingManager.getReactions(contact)
				.get(target);
		if (r == null) return 0;
		Integer n = r.get(HEART);
		return n == null ? 0 : n;
	}

	private MessageId deliver(BdfList body) throws Exception {
		return deliverAt(body, ++timestamp);
	}

	private MessageId deliverAt(BdfList body, long ts) throws Exception {
		Message m = clientHelper.createMessage(contactGroup.getId(), ts,
				clientHelper.toByteArray(body));
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
}

package org.zerionproject.app.messaging;

import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfDictionary;
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
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

/**
 * Only a private message the user chose to send is marked as a user message
 * for the cover traffic scheduler. Records this side creates while taking in
 * what a contact sent are not.
 */
@NotNullByDefault
public class UserMessageMarkingTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();
	private final List<MessageId> marked = new ArrayList<>();

	private ContactGroupScanTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private MessagingManager messagingManager;
	private MessagingManagerImpl sender;
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
		sender = new MessagingManagerImpl(db, clientHelper,
				device.getClientVersioningManager(),
				device.getMetadataParser(), device.getConversationManager(),
				device.getMessageTracker(), device.getContactGroupFactory(),
				device.getAutoDeleteManager(),
				device.getStreamingAttachmentWriter(),
				device.getIdentityManager(), validator,
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
	public void aPrivateMessageTheUserSendsIsMarked() throws Exception {
		PrivateMessage pm = device.getPrivateMessageFactory()
				.createPrivateMessage(contactGroup.getId(), ++timestamp,
						"hello", Collections.emptyList());

		sender.addLocalMessage(pm);

		assertEquals(Collections.singletonList(pm.getMessage().getId()),
				marked);
	}

	@Test
	public void aReactionTheUserAddsIsMarked() throws Exception {
		MessageId target = deliver(BdfList.of(PRIVATE_MESSAGE, "hi",
				new BdfList()));
		assertEquals("taking in a contact's message marks nothing", 0,
				marked.size());

		assertTrue(sender.addLocalReaction(contact, target, "heart"));

		assertEquals(1, marked.size());
	}

	@Test
	public void aMessageTakenInFromAContactIsNotMarked() throws Exception {
		deliver(BdfList.of(PRIVATE_MESSAGE, "hi", new BdfList()));
		int before = storedMessages();
		sender.receiveMeshMessage(contact, "over the mesh", ++timestamp,
				getRandomBytes(8), null);

		assertEquals("the mesh message is stored", before + 1,
				storedMessages());
		assertEquals(Collections.emptyList(), marked);
	}

	private int storedMessages() throws Exception {
		return db.transactionWithResult(true, txn ->
				db.getMessageIds(txn, contactGroup.getId()).size());
	}

	private MessageId deliver(BdfList body) throws Exception {
		Message m = clientHelper.createMessage(contactGroup.getId(),
				++timestamp, clientHelper.toByteArray(body));
		BdfDictionary meta = validator.validateToBdf(m, contactGroup)
				.getDictionary();
		db.transaction(false, txn -> {
			clientHelper.addLocalMessage(txn, m, meta, false, false);
			Metadata raw = db.getMessageMetadata(txn, m.getId());
			((IncomingMessageHook) sender).incomingMessage(txn, m, raw);
		});
		return m.getId();
	}
}

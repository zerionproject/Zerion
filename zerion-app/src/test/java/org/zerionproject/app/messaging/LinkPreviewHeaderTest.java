package org.zerionproject.app.messaging;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
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
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.messaging.LinkPreview;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.messaging.MessageTypes.LINK_PREVIEW_MESSAGE;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_ATTACHMENT_HEADERS;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MSG_TYPE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_PREVIEW_DESCRIPTION;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_PREVIEW_TITLE;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class LinkPreviewHeaderTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();

	private ContactGroupScanTestComponent device;
	private ClientHelper clientHelper;
	private DatabaseComponent db;
	private MessagingManager messagingManager;
	private PrivateMessageValidator validator;
	private ContactId contact;
	private Group group;
	private long clock = System.currentTimeMillis() - 60_000L;

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
	public void aReceivedLinkPreviewShowsInTheChat() throws Exception {
		MessageId text = receive(BdfList.of(PRIVATE_MESSAGE, "hello",
				new BdfList()));
		long withTextAt = clock;
		MessageId withText = receive(BdfList.of(LINK_PREVIEW_MESSAGE,
				"look at this", "https://example.org/", "A page",
				"About it"));
		long withoutTextAt = clock;
		MessageId withoutText = receive(BdfList.of(LINK_PREVIEW_MESSAGE,
				null, "https://example.org/b", "Another page", null,
				getRandomBytes(100)));

		Map<MessageId, PrivateMessageHeader> shown = openChat();

		assertEquals(3, shown.size());
		assertTrue(shown.containsKey(text));
		assertLinkPreview(shown.get(withText), withTextAt, false, true);
		assertLinkPreview(shown.get(withoutText), withoutTextAt, false,
				false);
		assertEquals(3, device.getConversationManager()
				.getMessageHeaders(contact).size());
	}

	@Test
	public void aSentLinkPreviewShowsInTheChat() throws Exception {
		MessageId text = receive(BdfList.of(PRIVATE_MESSAGE, "hello",
				new BdfList()));
		db.transaction(false, txn -> messagingManager
				.addLocalLinkPreviewMessage(txn, contact, "see",
						new LinkPreview("https://example.org/", "A page",
								null, null)));
		BdfDictionary previews = BdfDictionary.of(
				new BdfEntry(MSG_KEY_MSG_TYPE, LINK_PREVIEW_MESSAGE));
		Collection<MessageId> sent = db.transactionWithResult(true, txn ->
				clientHelper.getMessageIds(txn, group.getId(), previews));
		assertEquals(1, sent.size());
		MessageId preview = sent.iterator().next();
		long sentAt = db.transactionWithResult(true,
				txn -> db.getMessage(txn, preview)).getTimestamp();

		Map<MessageId, PrivateMessageHeader> shown = openChat();

		assertEquals(2, shown.size());
		assertTrue(shown.containsKey(text));
		assertLinkPreview(shown.get(preview), sentAt, true, true);
	}

	@Test
	public void aPreviewStoredWithHiddenCharactersIsCleanedWhenItIsRead()
			throws Exception {
		MessageId spoofed = storeAsAnEarlierVersionDid(
				"pay\u202Epal\u200B.com\u0007\tnews\uE000",
				"line one\u2028line\u202E two\r\n\u0085");
		MessageId hiddenOnly = storeAsAnEarlierVersionDid(
				"\u202E\u200B\u2066", "\u200F\u0007 ");
		MessageId withoutDescription = storeAsAnEarlierVersionDid(
				" A plain page ", null);
		MessageId current = receive(BdfList.of(LINK_PREVIEW_MESSAGE, null,
				"https://example.org/c", "A\u202E page", "About\u200B it"));

		Map<MessageId, LinkPreview> shown =
				messagingManager.getLinkPreviews(contact);

		assertEquals(4, shown.size());
		assertEquals("paypal.com news", shown.get(spoofed).getTitle());
		assertEquals("line one\nline two",
				shown.get(spoofed).getDescription());
		assertEquals("https://example.org/", shown.get(spoofed).getUrl());
		assertEquals("", shown.get(hiddenOnly).getTitle());
		assertNull(shown.get(hiddenOnly).getDescription());
		assertEquals("A plain page",
				shown.get(withoutDescription).getTitle());
		assertNull(shown.get(withoutDescription).getDescription());
		assertEquals("A page", shown.get(current).getTitle());
		assertEquals("About it", shown.get(current).getDescription());
	}

	@Test
	public void aPreviewIsShownWithItsPictureAndCleanedText() {
		byte[] picture = getRandomBytes(40);
		LinkPreview shown = MessagingManagerImpl.shownPreview(
				"https://example.org/", "Tit\u200Ele\u0000",
				"\u2067About\nit", picture);
		assertEquals(new LinkPreview("https://example.org/", "Title",
				"About\nit", picture), shown);
	}

	private MessageId storeAsAnEarlierVersionDid(String title,
			@Nullable String description) throws Exception {
		Message m = clientHelper.createMessage(group.getId(), clock++,
				clientHelper.toByteArray(BdfList.of(LINK_PREVIEW_MESSAGE,
						null, "https://example.org/", "A page", "About it")));
		BdfDictionary meta = validator.validateToBdf(m, group)
				.getDictionary();
		meta.put(MSG_KEY_PREVIEW_TITLE, title);
		if (description == null) meta.remove(MSG_KEY_PREVIEW_DESCRIPTION);
		else meta.put(MSG_KEY_PREVIEW_DESCRIPTION, description);
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, false, false));
		return m.getId();
	}

	private void assertLinkPreview(@Nullable PrivateMessageHeader h,
			long timestamp, boolean local, boolean hasText) throws Exception {
		assertNotNull(h);
		MessageId id = h.getId();
		assertFalse(clientHelper.getMessageMetadataAsDictionary(id)
				.containsKey(MSG_KEY_ATTACHMENT_HEADERS));
		assertEquals(group.getId(), h.getGroupId());
		assertEquals(timestamp, h.getTimestamp());
		assertEquals(local, h.isLocal());
		assertEquals(local, h.isRead());
		assertEquals(hasText, h.hasText());
		assertTrue(h.getAttachmentHeaders().isEmpty());
		assertEquals(NO_AUTO_DELETE_TIMER, h.getAutoDeleteTimer());
		assertNull(h.getReplyToId());
		assertFalse(h.isMesh());
	}

	private Map<MessageId, PrivateMessageHeader> openChat() throws Exception {
		Collection<ConversationMessageHeader> headers =
				db.transactionWithResult(true, txn ->
						messagingManager.getMessageHeaders(txn, contact));
		Map<MessageId, PrivateMessageHeader> byId = new HashMap<>();
		for (ConversationMessageHeader h : headers) {
			byId.put(h.getId(), (PrivateMessageHeader) h);
		}
		return byId;
	}

	private MessageId receive(BdfList body) throws Exception {
		Message m = clientHelper.createMessage(group.getId(), clock++,
				clientHelper.toByteArray(body));
		BdfDictionary meta = validator.validateToBdf(m, group)
				.getDictionary();
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, false, false));
		return m.getId();
	}
}

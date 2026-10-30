package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.MessageDeletedException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.app.api.messaging.ViewOnceMedia;
import org.zerionproject.app.api.messaging.ViewOnceNotSupportedException;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.api.attachment.MediaConstants.CHUNK_SIZE;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT_CHUNK;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.app.messaging.MessageTypes.VIEW_ONCE_OPENED;
import static org.zerionproject.app.messaging.MessagingConstants.BODY_SALT_LENGTH;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class ViewOnceTest extends BrambleTestCase {

	private static final long HOUR = 60L * 60 * 1000;

	private final File testDir = getTestDirectory();
	private final long now = System.currentTimeMillis();
	private final byte[] marker =
			("view-once-" + System.nanoTime()).getBytes(UTF_8);

	private Device me;
	private Device contact;

	@NotNullByDefault
	private static class Device {

		private final File dir;
		private final SecretKey key = getSecretKey();
		@Nullable
		private ContactGroupScanTestComponent component;
		private DatabaseComponent db;
		private ClientHelper clientHelper;
		private MessagingManager messagingManager;
		private PrivateMessageValidator validator;
		private ContactId contactId;
		private GroupId group;

		private Device(File dir) {
			this.dir = dir;
		}
	}

	@Before
	public void setUp() throws Exception {
		me = new Device(new File(testDir, "me"));
		contact = new Device(new File(testDir, "contact"));
		assertTrue(me.dir.mkdirs());
		assertTrue(contact.dir.mkdirs());
		Identity mine = build(me).getIdentityManager().createIdentity("Me");
		Identity theirs = build(contact).getIdentityManager()
				.createIdentity("Contact");
		start(me, mine);
		start(contact, theirs);
		addContact(me, theirs.getLocalAuthor(), mine, true);
		addContact(contact, mine.getLocalAuthor(), theirs, false);
		assertEquals(me.group, contact.group);
	}

	@After
	public void tearDown() throws Exception {
		stop(me);
		stop(contact);
		deleteTestDirectory(testDir);
	}

	@Test
	public void viewOnceNeedsVersion14AndIsRefusedToOlderContacts()
			throws Exception {
		assertEquals(14, MessagingManager.VIEW_ONCE_MIN_VERSION);
		assertTrue(MessagingManager.MINOR_VERSION
				>= MessagingManager.VIEW_ONCE_MIN_VERSION);
		assertTrue(seeing(me, 14).supportsViewOnce(me.contactId));
		assertFalse(seeing(me, 13).supportsViewOnce(me.contactId));
		assertFalse("the iOS client announces 3",
				seeing(me, 3).supportsViewOnce(me.contactId));
		for (int minor : new int[] {3, 12, 13}) {
			AttachmentHeader photo = photo(me);
			try {
				send(me, seeing(me, minor), photo);
				fail("view once was sent to a contact at " + minor);
			} catch (ViewOnceNotSupportedException expected) {
			}
		}
		assertTrue(outbox(me).isEmpty());
	}

	@Test
	public void theBodyCarriesTheFlagAfterTheSaltAndNoText()
			throws Exception {
		MessageId sent = send(me, seeing(me, 14), photo(me));
		BdfList body = body(me, sent);
		assertEquals(7, body.size());
		assertEquals(PRIVATE_MESSAGE, (int) body.getInt(0));
		assertNull(body.getOptionalString(1));
		assertEquals(1, body.getList(2).size());
		assertEquals(BODY_SALT_LENGTH, body.getRaw(5).length);
		assertEquals(1L, (long) body.getLong(6));
		PrivateMessageHeader h = header(me, sent);
		assertTrue(h.isViewOnce());
		assertFalse(h.isViewOnceOpened());
	}

	@Test
	public void onlyOnePhotoOrVideoWithoutTextCanBeViewOnce()
			throws Exception {
		PrivateMessageFactory factory = factory(me);
		for (String type : new String[] {"audio/ogg", "application/pdf",
				"text/plain"}) {
			try {
				factory.createViewOncePrivateMessage(me.group, now,
						new AttachmentHeader(me.group,
								new MessageId(getRandomId()), type),
						NO_AUTO_DELETE_TIMER, null);
				fail(type + " was made view once");
			} catch (IllegalArgumentException expected) {
			}
		}
		Message picture = attachment(contact, "image/jpeg", now - HOUR);
		assertEquals(MessageState.DELIVERED, deliver(contact, picture));
		BdfList one = header(picture.getId(), "image/jpeg");
		BdfList two = BdfList.of(one, header(picture.getId(), "image/jpeg"));
		byte[] salt = getRandomBytes(BODY_SALT_LENGTH);
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(PRIVATE_MESSAGE, "caption", BdfList.of(one), null,
						null, salt, 1), now - HOUR + 1));
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(PRIVATE_MESSAGE, null, two, null, null, salt, 1),
				now - HOUR + 2));
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(PRIVATE_MESSAGE, null, BdfList.of(one), null, null,
						null, 1), now - HOUR + 3));
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(PRIVATE_MESSAGE, null, BdfList.of(one), null, null,
						salt, 2), now - HOUR + 4));
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(PRIVATE_MESSAGE, null, BdfList.of(one), null, null,
						salt, 1, null), now - HOUR + 5));
		Message sound = attachment(contact, "audio/ogg", now - HOUR + 6);
		assertEquals(MessageState.DELIVERED, deliver(contact, sound));
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(PRIVATE_MESSAGE, null,
						BdfList.of(header(sound.getId(), "audio/ogg")), null,
						null, salt, 1), now - HOUR + 7));
		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(PRIVATE_MESSAGE, null, BdfList.of(one), null, null,
						salt, 1), now - HOUR + 8));
	}

	@Test
	public void theReceiptRecordIsValidatedLikeADelete() throws Exception {
		byte[] target = getRandomId();
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(VIEW_ONCE_OPENED, getRandomBytes(31)), now - 3));
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(VIEW_ONCE_OPENED, target, 1), now - 2));
		assertEquals(MessageState.INVALID, deliverBody(contact,
				BdfList.of(VIEW_ONCE_OPENED), now - 1));
		assertEquals(MessageState.DELIVERED, deliverBody(contact,
				BdfList.of(VIEW_ONCE_OPENED, target), now));
	}

	@Test
	public void theContactSeesAViewOnceTileUntilItIsOpened()
			throws Exception {
		MessageId sent = send(me, seeing(me, 14), photo(me));
		transfer(me, contact);

		PrivateMessageHeader h = header(contact, sent);
		assertTrue(h.isViewOnce());
		assertFalse(h.isViewOnceOpened());
		assertFalse(h.hasText());
		assertEquals("image/jpeg",
				h.getAttachmentHeaders().get(0).getContentType());
	}

	@Test
	public void openingHandsOverTheMediaOnceAndErasesItAtTheSameTime()
			throws Exception {
		AttachmentHeader photo = photo(me);
		MessageId sent = send(me, seeing(me, 14), photo);
		transfer(me, contact);
		assertTrue(stored(contact, marker));

		ViewOnceMedia media =
				contact.messagingManager.openViewOnce(contact.contactId, sent);

		assertNotNull(media);
		assertEquals("image/jpeg", media.getContentType());
		assertArrayEquals(pictureBytes(), media.getData());
		assertFalse("the media is still stored", stored(contact, marker));
		assertBodyErased(contact, photo.getMessageId());
		assertTrue(header(contact, sent).isViewOnceOpened());
		assertNull("opened twice", contact.messagingManager
				.openViewOnce(contact.contactId, sent));
		restart(contact);
		assertTrue(header(contact, sent).isViewOnceOpened());
		assertNull(contact.messagingManager.openViewOnce(contact.contactId,
				sent));
		assertFalse(stored(contact, marker));
	}

	@Test
	public void theErasedPartsKeepNoLengthAndAreStillAcknowledged()
			throws Exception {
		AttachmentHeader photo = photo(me);
		MessageId sent = send(me, seeing(me, 14), photo);
		transfer(me, contact);

		assertNotNull(contact.messagingManager.openViewOnce(
				contact.contactId, sent));

		assertEquals(0, storedLength(contact, photo.getMessageId()));
		Ack ack = contact.db.transactionWithNullableResult(false, txn ->
				contact.db.generateAck(txn, contact.contactId, 1000));
		assertNotNull(ack);
		assertTrue(ack.getMessageIds().contains(sent));
		assertTrue("the erased photo is never acknowledged",
				ack.getMessageIds().contains(photo.getMessageId()));
		me.db.transaction(false, txn ->
				me.db.receiveAck(txn, me.contactId, ack));
		awaitNotStored(me, marker);
		assertEquals(0, storedLength(me, photo.getMessageId()));
		assertTrue("the erased photo is offered again", outbox(me).isEmpty());
	}

	private long storedLength(Device d, MessageId id) throws Exception {
		return d.db.transactionWithResult(true, txn -> {
			Connection c = (Connection) txn.unbox();
			try (java.sql.PreparedStatement s = c.prepareStatement(
					"SELECT length FROM messages WHERE messageId = ?")) {
				s.setBytes(1, id.getBytes());
				try (ResultSet rs = s.executeQuery()) {
					assertTrue(rs.next());
					return rs.getLong(1);
				}
			}
		});
	}

	@Test
	public void aContactsViewOnceNamingMyOwnPhotoIsRefusedAndErasesNothing()
			throws Exception {
		AttachmentHeader mine = photo(me);
		PrivateMessage plain = factory(me).createSaltedPrivateMessage(
				me.group, now, null, singletonList(mine),
				NO_AUTO_DELETE_TIMER, null);
		me.db.transaction(false, txn ->
				me.messagingManager.addLocalMessage(txn, plain));
		MessageId forged = deliverBodyId(me, BdfList.of(PRIVATE_MESSAGE, null,
				BdfList.of(header(mine.getMessageId(), "image/jpeg")), null,
				null, getRandomBytes(BODY_SALT_LENGTH), 1), now + 1);
		assertTrue(header(me, forged).isViewOnce());

		assertNull(me.messagingManager.openViewOnce(me.contactId, forged));

		assertTrue("my own photo was erased", stored(me, marker));
		assertFalse(header(me, forged).isViewOnceOpened());
		assertTrue(localControls(me, VIEW_ONCE_OPENED).isEmpty());
		assertTrue(me.db.transactionWithResult(true, txn ->
				me.messagingManager.getUnclearedViewOnceMedia(txn)).isEmpty());
	}

	@Test
	public void readingTheMediaSizesTheCopyOnce() throws Exception {
		byte[] data = getRandomBytes(1024 * 1024);
		int[] reads = new int[1];
		ByteArrayInputStream in = new ByteArrayInputStream(data) {
			@Override
			public synchronized int read(byte[] b, int off, int len) {
				reads[0]++;
				return super.read(b, off, len);
			}

			@Override
			public synchronized int read() {
				reads[0]++;
				return super.read();
			}
		};

		byte[] read = MessagingManagerImpl.readMedia(in);

		assertArrayEquals(data, read);
		assertTrue("read in " + reads[0] + " steps", reads[0] <= 3);
		java.io.InputStream unknownLength = new java.io.FilterInputStream(
				new ByteArrayInputStream(data)) {
			@Override
			public int available() {
				return 0;
			}
		};
		assertArrayEquals(data, MessagingManagerImpl.readMedia(unknownLength));
	}

	@Test
	public void anAcknowledgementOfSomethingElseLooksNoFurther()
			throws Exception {
		AttachmentHeader photo = photo(me);
		MessageId sent = send(me, seeing(me, 14), photo);
		transfer(me, contact);
		assertTrue(meta(me, sent).getBoolean("viewOncePending", false));
		markEverythingSeen(me);

		Collection<MessageId> unrelated = me.db.transactionWithResult(true,
				txn -> impl(me).findDeliveredViewOnce(txn, me.contactId,
						singletonList(new MessageId(getRandomId()))));
		Collection<MessageId> related = me.db.transactionWithResult(true,
				txn -> impl(me).findDeliveredViewOnce(txn, me.contactId,
						singletonList(photo.getMessageId())));

		assertTrue(unrelated.isEmpty());
		assertEquals(singletonList(sent), new ArrayList<>(related));
		me.db.transaction(false, txn ->
				impl(me).clearViewOnceMedia(txn, me.contactId, related));
		assertFalse(meta(me, sent).containsKey("viewOncePending"));
		assertFalse(stored(me, marker));
	}

	@Test
	public void anUnreadableMessageDoesNotStopTheSweep() throws Exception {
		byte[] other = ("other-" + System.nanoTime()).getBytes(UTF_8);
		MessageId broken = send(me, seeing(me, 14), photo(me));
		MessageId readable = send(me, seeing(me, 14), photo(me, other));
		transfer(me, contact);
		markEverythingSeen(me);
		org.zerionproject.core.api.db.Metadata bad =
				new org.zerionproject.core.api.db.Metadata();
		bad.put("broken", new byte[] {(byte) 0xFF});
		me.db.transaction(false, txn ->
				me.db.mergeMessageMetadata(txn, broken, bad));

		Collection<MessageId> delivered = me.db.transactionWithResult(true,
				txn -> impl(me).findDeliveredViewOnce(txn, me.contactId,
						null));
		me.db.transaction(false, txn ->
				impl(me).clearViewOnceMedia(txn, me.contactId, delivered));

		assertEquals(singletonList(readable), new ArrayList<>(delivered));
		assertFalse("the readable message kept its media", stored(me, other));
	}

	@Test
	public void theTileAppearsOnlyOnceItsMediaHasArrived() throws Exception {
		AttachmentHeader photo = photo(me);
		MessageId sent = send(me, seeing(me, 14), photo);
		List<Message> batch = outbox(me);
		Message pm = null;
		Message part = null;
		for (Message m : batch) {
			if (m.getId().equals(sent)) pm = m;
			else if (m.getId().equals(photo.getMessageId())) part = m;
		}
		assertNotNull(pm);
		assertNotNull(part);
		Message message = pm;
		contact.db.transaction(false, txn ->
				contact.db.receiveMessage(txn, contact.contactId, message));
		Thread.sleep(300);

		assertFalse("shown before the photo arrived",
				conversation(contact).contains(sent));

		assertEquals(MessageState.DELIVERED, deliver(contact, part));
		awaitSettled(contact, sent);
		assertTrue(conversation(contact).contains(sent));
		assertNotNull(contact.messagingManager.openViewOnce(
				contact.contactId, sent));
	}

	@Test
	public void unclearedViewOnceMediaIsNamedForBackups() throws Exception {
		AttachmentHeader photo = photo(me);
		MessageId sent = send(me, seeing(me, 14), photo);
		transfer(me, contact);
		assertEquals(singletonList(photo.getMessageId()), new ArrayList<>(
				me.db.transactionWithResult(true, txn ->
						me.messagingManager.getUnclearedViewOnceMedia(txn))));
		assertEquals(singletonList(photo.getMessageId()), new ArrayList<>(
				contact.db.transactionWithResult(true, txn -> contact
						.messagingManager.getUnclearedViewOnceMedia(txn))));

		assertNotNull(contact.messagingManager.openViewOnce(
				contact.contactId, sent));
		transfer(contact, me);

		assertTrue(me.db.transactionWithResult(true, txn ->
				me.messagingManager.getUnclearedViewOnceMedia(txn)).isEmpty());
		assertTrue(contact.db.transactionWithResult(true, txn -> contact
				.messagingManager.getUnclearedViewOnceMedia(txn)).isEmpty());
	}

	@Test
	public void everyChunkOfAnUnopenedVideoIsNamedForBackups()
			throws Exception {
		byte[] video = videoBytes();
		AttachmentHeader clip = me.messagingManager.addLocalAttachmentStreaming(
				me.group, now - HOUR, "video/mp4",
				new ByteArrayInputStream(video), video.length, null);
		send(me, seeing(me, 14), clip);

		Collection<MessageId> named = me.db.transactionWithResult(true, txn ->
				me.messagingManager.getUnclearedViewOnceMedia(txn));

		assertEquals(chunks(me), named.size());
		assertFalse("the manifest holds no media",
				named.contains(clip.getMessageId()));
	}

	@Test
	public void aBuildWithoutViewOnceAnnouncesVersion13() {
		assertEquals(MessagingManager.MINOR_VERSION,
				MessagingModule.announcedMinorVersion(flags(true, true, true)));
		assertEquals(13,
				MessagingModule.announcedMinorVersion(flags(true, true, false)));
		assertEquals(2,
				MessagingModule.announcedMinorVersion(flags(true, false, true)));
		assertEquals(0,
				MessagingModule.announcedMinorVersion(flags(false, true, true)));
	}

	private static org.zerionproject.core.api.FeatureFlags flags(
			boolean images, boolean disappearing, boolean viewOnce) {
		return new org.zerionproject.core.api.FeatureFlags() {
			@Override
			public boolean shouldEnableImageAttachments() {
				return images;
			}

			@Override
			public boolean shouldEnableProfilePictures() {
				return true;
			}

			@Override
			public boolean shouldEnableDisappearingMessages() {
				return disappearing;
			}

			@Override
			public boolean shouldEnableI2p() {
				return false;
			}

			@Override
			public boolean shouldEnableDebugCountryOverride() {
				return false;
			}

			@Override
			public boolean shouldEnableViewOnce() {
				return viewOnce;
			}
		};
	}

	@Test
	public void viewOnceCountsAsOneUnreadUntilReadAndTheReceiptNever()
			throws Exception {
		MessageId sent = send(me, seeing(me, 14), photo(me));
		transfer(me, contact);
		assertEquals(1, count(contact).getUnreadCount());

		requireComponent(contact).getConversationManager().setReadFlag(
				contact.group, sent, true);
		assertEquals(0, count(contact).getUnreadCount());
		assertNotNull(contact.messagingManager.openViewOnce(
				contact.contactId, sent));
		transfer(contact, me);

		assertEquals(0, count(contact).getUnreadCount());
		assertEquals(1, count(contact).getMsgCount());
		assertEquals(0, count(me).getUnreadCount());
		assertEquals(1, count(me).getMsgCount());
		me.db.transaction(false, txn -> me.messagingManager.deleteMessages(
				txn, me.contactId, java.util.Collections.singleton(
						new MessageId(getRandomId()))));
		assertEquals(0, count(me).getUnreadCount());
	}

	@Test
	public void anUnreadViewOnceOpenedStraightAwayLeavesNoUnreadCount()
			throws Exception {
		MessageId sent = send(me, seeing(me, 14), photo(me));
		transfer(me, contact);
		requireComponent(contact).getConversationManager().setReadFlag(
				contact.group, sent, true);
		assertNotNull(contact.messagingManager.openViewOnce(
				contact.contactId, sent));
		contact.db.transaction(false, txn -> contact.messagingManager
				.deleteMessages(txn, contact.contactId,
						java.util.Collections.singleton(sent)));

		assertEquals(0, count(contact).getUnreadCount());
		assertEquals(0, count(contact).getMsgCount());
	}

	private org.zerionproject.app.api.client.MessageTracker.GroupCount count(
			Device d) throws Exception {
		return d.db.transactionWithResult(true, txn ->
				d.messagingManager.getGroupCount(txn, d.contactId));
	}

	@Test
	public void aVideoInChunksIsErasedWithEveryChunk() throws Exception {
		byte[] video = videoBytes();
		AttachmentHeader clip = me.messagingManager.addLocalAttachmentStreaming(
				me.group, now - HOUR, "video/mp4",
				new ByteArrayInputStream(video), video.length, null);
		MessageId sent = send(me, seeing(me, 14), clip);
		transfer(me, contact);
		assertTrue(stored(contact, marker));
		assertTrue(chunks(contact) > 1);

		ViewOnceMedia media =
				contact.messagingManager.openViewOnce(contact.contactId, sent);

		assertNotNull(media);
		assertTrue(media.isVideo());
		assertArrayEquals(video, media.getData());
		assertFalse("a chunk is still stored", stored(contact, marker));
	}

	@Test
	public void theSenderCannotOpenItsOwnViewOnceMessage() throws Exception {
		MessageId sent = send(me, seeing(me, 14), photo(me));

		assertNull(me.messagingManager.openViewOnce(me.contactId, sent));
		assertFalse(header(me, sent).isViewOnceOpened());
		assertTrue(localControls(me, VIEW_ONCE_OPENED).isEmpty());
	}

	@Test
	public void anOrdinaryPhotoCannotBeOpenedAsViewOnce() throws Exception {
		Message picture = attachment(contact, "image/jpeg", now - HOUR);
		deliver(contact, picture);
		MessageId plain = deliverBodyId(contact, BdfList.of(PRIVATE_MESSAGE,
				null, BdfList.of(header(picture.getId(), "image/jpeg")), null,
				null, getRandomBytes(BODY_SALT_LENGTH)), now - HOUR + 1);

		assertNull(contact.messagingManager.openViewOnce(contact.contactId,
				plain));
		assertFalse(header(contact, plain).isViewOnce());
		assertTrue(stored(contact, picture.getBody()));
	}

	@Test
	public void theReceiptTurnsTheSendersCopyIntoViewed() throws Exception {
		AttachmentHeader photo = photo(me);
		MessageId sent = send(me, seeing(me, 14), photo);
		transfer(me, contact);
		assertNotNull(contact.messagingManager.openViewOnce(
				contact.contactId, sent));
		assertEquals(1, localControls(contact, VIEW_ONCE_OPENED).size());

		transfer(contact, me);

		assertTrue(header(me, sent).isViewOnceOpened());
		assertFalse("the sender still holds the media", stored(me, marker));
		awaitGone(me, VIEW_ONCE_OPENED);
	}

	@Test
	public void aReceiptForAMessageTheSenderDidNotSendChangesNothing()
			throws Exception {
		MessageId theirs = send(contact, seeing(contact, 14), photo(contact));
		transfer(contact, me);

		assertEquals(MessageState.DELIVERED, deliverBody(me,
				BdfList.of(VIEW_ONCE_OPENED, theirs.getBytes()), now));

		assertFalse(header(me, theirs).isViewOnceOpened());
		assertTrue(stored(me, marker));
	}

	@Test
	public void theSenderKeepsTheMediaOnlyUntilEveryPartHasArrived()
			throws Exception {
		AttachmentHeader photo = photo(me);
		MessageId sent = send(me, seeing(me, 14), photo);
		transfer(me, contact);

		acknowledge(me, sent);
		Thread.sleep(300);
		assertTrue("erased before the photo itself arrived",
				stored(me, marker));

		acknowledge(me, photo.getMessageId());
		awaitNotStored(me, marker);
		PrivateMessageHeader h = header(me, sent);
		assertTrue(h.isViewOnce());
		assertFalse(h.isViewOnceOpened());
		assertBodyErased(me, photo.getMessageId());
	}

	@Test
	public void aSenderThatMissedTheAckErasesTheMediaWhenItStarts()
			throws Exception {
		AttachmentHeader photo = photo(me);
		MessageId sent = send(me, seeing(me, 14), photo);
		transfer(me, contact);
		me.db.transaction(false, txn -> {
			Connection c = (Connection) txn.unbox();
			try (Statement s = c.createStatement()) {
				s.executeUpdate("UPDATE statuses SET seen = TRUE");
			}
		});
		Thread.sleep(300);
		assertTrue("erased without an acknowledgement", stored(me, marker));

		restart(me);

		assertFalse(stored(me, marker));
		assertTrue(header(me, sent).isViewOnce());
		assertBodyErased(me, photo.getMessageId());
	}

	@Test
	public void deleteForEveryoneStillRemovesAViewOnceMessage()
			throws Exception {
		AttachmentHeader photo = photo(me);
		MessageId sent = send(me, seeing(me, 14), photo);
		transfer(me, contact);
		returnAcks(contact, me);
		awaitNotStored(me, marker);

		assertTrue(seeing(me, 14).deleteForEveryone(me.contactId,
				singletonList(sent)));
		transfer(me, contact);

		assertFalse(conversation(me).contains(sent));
		assertFalse(conversation(contact).contains(sent));
		assertFalse(stored(contact, marker));
		assertGone(me, photo.getMessageId());
		assertGone(contact, photo.getMessageId());
	}

	@Test
	public void aViewedMessageCanStillBeDeletedOnBothSides()
			throws Exception {
		AttachmentHeader photo = photo(me);
		MessageId sent = send(me, seeing(me, 14), photo);
		transfer(me, contact);
		assertNotNull(contact.messagingManager.openViewOnce(
				contact.contactId, sent));
		transfer(contact, me);

		contact.db.transaction(false, txn -> contact.messagingManager
				.deleteMessages(txn, contact.contactId,
						java.util.Collections.singleton(sent)));
		me.db.transaction(false, txn -> me.messagingManager
				.deleteMessages(txn, me.contactId,
						java.util.Collections.singleton(sent)));

		assertFalse(conversation(contact).contains(sent));
		assertFalse(conversation(me).contains(sent));
		assertGone(contact, photo.getMessageId());
		assertGone(me, photo.getMessageId());
	}

	@Test
	public void theDisappearingTimerTravelsWithAViewOnceMessage()
			throws Exception {
		long timer = HOUR;
		AttachmentHeader photo = photo(me);
		PrivateMessage pm = factory(me).createViewOncePrivateMessage(me.group,
				now, photo, timer, null);
		MessagingManagerImpl seen = seeing(me, 14);
		me.db.transaction(false, txn -> seen.addLocalMessage(txn, pm));
		MessageId sent = pm.getMessage().getId();
		transfer(me, contact);

		assertEquals(timer, header(contact, sent).getAutoDeleteTimer());
		assertTrue(header(contact, sent).isViewOnce());
	}

	private AttachmentHeader photo(Device d) throws Exception {
		return photo(d, marker);
	}

	private AttachmentHeader photo(Device d, byte[] mark) throws Exception {
		return d.messagingManager.addLocalAttachment(d.group, now - HOUR,
				"image/jpeg", new ByteArrayInputStream(pictureBytes(mark)));
	}

	private byte[] pictureBytes() {
		return pictureBytes(marker);
	}

	private static byte[] pictureBytes(byte[] mark) {
		byte[] b = new byte[2048];
		b[0] = (byte) 0xFF;
		b[1] = (byte) 0xD8;
		b[2] = (byte) 0xFF;
		System.arraycopy(mark, 0, b, 100, mark.length);
		return b;
	}

	private MessagingManagerImpl impl(Device d) {
		return (MessagingManagerImpl) d.messagingManager;
	}

	private void markEverythingSeen(Device d) throws Exception {
		d.db.transaction(false, txn -> {
			Connection c = (Connection) txn.unbox();
			try (Statement s = c.createStatement()) {
				s.executeUpdate("UPDATE statuses SET seen = TRUE");
			}
		});
	}

	private BdfDictionary meta(Device d, MessageId id) throws Exception {
		return d.db.transactionWithResult(true, txn ->
				d.clientHelper.getMessageMetadataAsDictionary(txn, id));
	}

	private byte[] videoBytes() {
		byte[] b = new byte[CHUNK_SIZE * 2 + 1000];
		for (int i = 0; i + marker.length < b.length; i += CHUNK_SIZE / 2) {
			System.arraycopy(marker, 0, b, i + 10, marker.length);
		}
		return b;
	}

	private MessageId send(Device d, MessagingManagerImpl seen,
			AttachmentHeader media) throws Exception {
		PrivateMessage pm = factory(d).createViewOncePrivateMessage(d.group,
				now, media, NO_AUTO_DELETE_TIMER, null);
		d.db.transaction(false, txn -> seen.addLocalMessage(txn, pm));
		return pm.getMessage().getId();
	}

	private PrivateMessageFactory factory(Device d) {
		return requireComponent(d).getPrivateMessageFactory();
	}

	private static BdfList header(MessageId id, String type) {
		return BdfList.of(id.getBytes(), type);
	}

	private Message attachment(Device d, String type, long ts)
			throws Exception {
		byte[] descriptor = d.clientHelper.toByteArray(
				BdfList.of(ATTACHMENT, type));
		byte[] body = new byte[descriptor.length + 3];
		System.arraycopy(descriptor, 0, body, 0, descriptor.length);
		body[descriptor.length] = 9;
		return d.clientHelper.createMessage(d.group, ts, body);
	}

	private BdfList body(Device d, MessageId id) throws Exception {
		Message m = d.db.transactionWithResult(true, txn ->
				d.db.getMessage(txn, id));
		return d.clientHelper.toList(m);
	}

	private void transfer(Device from, Device to) throws Exception {
		List<Message> batch = outbox(from);
		to.db.transaction(false, txn -> {
			for (Message m : batch) to.db.receiveMessage(txn, to.contactId, m);
		});
		for (Message m : batch) awaitSettled(to, m.getId());
	}

	private void awaitSettled(Device d, MessageId id) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			MessageState s;
			try {
				s = d.db.transactionWithResult(true, txn ->
						d.db.getMessageState(txn, id));
			} catch (NoSuchMessageException removedAfterDelivery) {
				return;
			}
			if (s != MessageState.UNKNOWN && s != MessageState.PENDING) return;
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("the message was never delivered");
			}
			Thread.sleep(20);
		}
	}

	private List<Message> outbox(Device d) throws Exception {
		Collection<Message> batch = d.db.transactionWithNullableResult(false,
				txn -> d.db.generateBatch(txn, d.contactId, 10_000_000L,
						60_000L));
		List<Message> mine = new ArrayList<>();
		if (batch == null) return mine;
		for (Message m : batch) {
			if (m.getGroupId().equals(d.group)) mine.add(m);
		}
		return mine;
	}

	private void acknowledge(Device d, MessageId m) throws Exception {
		d.db.transaction(false, txn -> d.db.receiveAck(txn, d.contactId,
				new Ack(singletonList(m))));
	}

	private void returnAcks(Device from, Device to) throws Exception {
		Ack ack = from.db.transactionWithNullableResult(false, txn ->
				from.db.generateAck(txn, from.contactId, 1000));
		assertNotNull(ack);
		to.db.transaction(false, txn ->
				to.db.receiveAck(txn, to.contactId, ack));
	}

	private Collection<MessageId> localControls(Device d, int type)
			throws Exception {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry("messageType", type),
				new BdfEntry("local", true));
		return d.db.transactionWithResult(true, txn ->
				d.clientHelper.getMessageIds(txn, d.group, query));
	}

	private int chunks(Device d) throws Exception {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry("messageType", ATTACHMENT_CHUNK));
		return d.db.transactionWithResult(true, txn ->
				d.clientHelper.getMessageIds(txn, d.group, query).size());
	}

	private void awaitGone(Device d, int type) throws Exception {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry("messageType", type));
		long deadline = System.currentTimeMillis() + 20_000;
		while (!d.db.transactionWithResult(true, txn ->
				d.clientHelper.getMessageIds(txn, d.group, query)).isEmpty()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("the receipt record is still kept");
			}
			Thread.sleep(20);
		}
	}

	private Collection<MessageId> conversation(Device d) throws Exception {
		return d.db.transactionWithResult(true, txn ->
				d.messagingManager.getMessageIds(txn, d.contactId));
	}

	private PrivateMessageHeader header(Device d, MessageId id)
			throws Exception {
		Collection<ConversationMessageHeader> headers =
				d.db.transactionWithResult(true, txn ->
						d.messagingManager.getMessageHeaders(txn,
								d.contactId));
		for (ConversationMessageHeader h : headers) {
			if (h.getId().equals(id)) return (PrivateMessageHeader) h;
		}
		throw new AssertionError("no header for the message");
	}

	private void assertBodyErased(Device d, MessageId m) throws Exception {
		d.db.transaction(true, txn -> {
			try {
				d.db.getMessage(txn, m);
				fail("the stored body is still readable");
			} catch (MessageDeletedException expected) {
			}
		});
	}

	private void assertGone(Device d, MessageId m) throws Exception {
		d.db.transaction(true, txn -> {
			try {
				d.db.getMessage(txn, m);
				fail("the attachment row is still there");
			} catch (NoSuchMessageException expected) {
			}
		});
	}

	private boolean stored(Device d, byte[] needle) throws Exception {
		return d.db.transactionWithResult(true, txn -> {
			Connection c = (Connection) txn.unbox();
			for (String sql : new String[] {"SELECT raw FROM messages",
					"SELECT value FROM messageMetadata"}) {
				try (Statement s = c.createStatement();
						ResultSet rs = s.executeQuery(sql)) {
					while (rs.next()) {
						byte[] value = rs.getBytes(1);
						if (value != null && contains(value, needle)) {
							return true;
						}
					}
				}
			}
			return false;
		});
	}

	private void awaitNotStored(Device d, byte[] needle) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (stored(d, needle)) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("the media is still stored");
			}
			Thread.sleep(20);
		}
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private MessageId deliverBodyId(Device d, BdfList body, long ts)
			throws Exception {
		Message m = create(d, body, ts);
		assertEquals(MessageState.DELIVERED, deliver(d, m));
		return m.getId();
	}

	private MessageState deliverBody(Device d, BdfList body, long ts)
			throws Exception {
		return deliver(d, create(d, body, ts));
	}

	private Message create(Device d, BdfList body, long ts)
			throws FormatException {
		return d.clientHelper.createMessage(d.group, ts,
				d.clientHelper.toByteArray(body));
	}

	private MessageState deliver(Device d, Message m) throws Exception {
		d.db.transaction(false, txn -> d.db.receiveMessage(txn, d.contactId,
				m));
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			MessageState s;
			try {
				s = d.db.transactionWithResult(true, txn ->
						d.db.getMessageState(txn, m.getId()));
			} catch (NoSuchMessageException removedAfterDelivery) {
				return MessageState.DELIVERED;
			}
			if (s != MessageState.UNKNOWN && s != MessageState.PENDING) {
				return s;
			}
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("the message was never validated");
			}
			Thread.sleep(20);
		}
	}

	private MessagingManagerImpl seeing(Device d, int minorVersion) {
		ContactGroupScanTestComponent c = requireComponent(d);
		ClientVersioningManager real = c.getClientVersioningManager();
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
		return new MessagingManagerImpl(d.db, d.clientHelper, seen,
				c.getMetadataParser(), c.getConversationManager(),
				c.getMessageTracker(), c.getContactGroupFactory(),
				c.getAutoDeleteManager(), c.getStreamingAttachmentWriter(),
				c.getIdentityManager(), d.validator, c.getCryptoComponent());
	}

	private static ContactGroupScanTestComponent requireComponent(Device d) {
		ContactGroupScanTestComponent c = d.component;
		assertNotNull(c);
		return c;
	}

	private ContactGroupScanTestComponent build(Device d) {
		ContactGroupScanTestComponent c =
				DaggerContactGroupScanTestComponent.builder()
						.testDatabaseConfigModule(
								new TestDatabaseConfigModule(d.dir))
						.build();
		ContactGroupScanTestComponent.Helper.injectEagerSingletons(c);
		d.component = c;
		return c;
	}

	private void start(Device d, @Nullable Identity identity)
			throws Exception {
		ContactGroupScanTestComponent c = requireComponent(d);
		if (identity != null) {
			c.getIdentityManager().registerIdentity(identity);
		}
		LifecycleManager lifecycle = c.getLifecycleManager();
		lifecycle.startServices(d.key);
		lifecycle.waitForStartup();
		bind(d);
	}

	private void bind(Device d) {
		ContactGroupScanTestComponent c = requireComponent(d);
		d.db = c.getDatabaseComponent();
		d.clientHelper = c.getClientHelper();
		d.messagingManager = c.getMessagingManager();
		d.validator = c.getPrivateMessageValidator();
	}

	private void stop(@Nullable Device d) throws Exception {
		if (d == null || d.component == null) return;
		LifecycleManager lifecycle = d.component.getLifecycleManager();
		lifecycle.stopServices();
		lifecycle.waitForShutdown();
		d.component = null;
	}

	private void restart(Device d) throws Exception {
		stop(d);
		build(d);
		start(d, null);
	}

	private void addContact(Device d, Author remote, Identity local,
			boolean alice) throws Exception {
		ContactGroupScanTestComponent c = requireComponent(d);
		d.contactId = c.getContactManager().addContact(remote, local.getId(),
				getSecretKey(), now, alice, true, true);
		Group g = d.messagingManager.getContactGroup(
				c.getContactManager().getContact(d.contactId));
		d.group = g.getId();
		d.db.transaction(false, txn -> d.db.setGroupVisibility(txn,
				d.contactId, g.getId(), Group.Visibility.SHARED));
	}
}

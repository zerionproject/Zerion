package org.zerionproject.app.messaging;

import org.zerionproject.core.api.UniqueId;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.SyncRecordWriter;
import org.zerionproject.core.api.sync.SyncRecordWriterFactory;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import static org.zerionproject.core.api.sync.SyncConstants.MAX_DELIVERABLE_MESSAGE_BODY_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_SYNC_RECORD_BYTES;
import static org.zerionproject.core.api.sync.SyncConstants.MESSAGE_HEADER_LENGTH;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.getRandomString;
import static org.zerionproject.app.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.MAX_AUTO_DELETE_TIMER_MS;
import static org.zerionproject.app.api.messaging.MessagingConstants.MAX_ATTACHMENTS_PER_MESSAGE;
import static org.zerionproject.app.api.messaging.MessagingConstants.MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH;
import static org.zerionproject.app.messaging.MessagingConstants.BODY_SALT_LENGTH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MessageSizeIntegrationTest extends BrambleTestCase {

	@Inject
	CryptoComponent crypto;
	@Inject
	PrivateMessageFactory privateMessageFactory;
	@Inject
	SyncRecordWriterFactory syncRecordWriterFactory;
	@Inject
	ClientHelper clientHelper;

	public MessageSizeIntegrationTest() {
		MessageSizeIntegrationTestComponent component =
				DaggerMessageSizeIntegrationTestComponent.builder().build();
		MessageSizeIntegrationTestComponent.Helper
				.injectEagerSingletons(component);
		component.inject(this);
	}

	@Test
	public void testLegacyPrivateMessageFitsIntoRecord() throws Exception {

		GroupId groupId = new GroupId(getRandomId());
		long timestamp = Long.MAX_VALUE;
		String text = getRandomString(MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH);
		PrivateMessage message = privateMessageFactory
				.createLegacyPrivateMessage(groupId, timestamp, text);

		int length = message.getMessage().getRawLength();
		assertTrue(length > UniqueId.LENGTH + 8
				+ MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH);
		assertFitsOneSyncRecord(message.getMessage());
	}

	@Test
	public void testPrivateMessageFitsIntoRecord() throws Exception {

		GroupId groupId = new GroupId(getRandomId());
		long timestamp = Long.MAX_VALUE;
		String text = getRandomString(MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH);

		List<AttachmentHeader> headers = new ArrayList<>();
		for (int i = 0; i < MAX_ATTACHMENTS_PER_MESSAGE; i++) {
			headers.add(new AttachmentHeader(groupId,
					new MessageId(getRandomId()),
					getRandomString(MAX_CONTENT_TYPE_BYTES)));
		}
		PrivateMessage message = privateMessageFactory.createPrivateMessage(
				groupId, timestamp, text, headers, MAX_AUTO_DELETE_TIMER_MS);

		int length = message.getMessage().getRawLength();
		assertTrue(length > UniqueId.LENGTH + 8
				+ MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH + MAX_ATTACHMENTS_PER_MESSAGE
				* (UniqueId.LENGTH + MAX_CONTENT_TYPE_BYTES) + 4);
		assertFitsOneSyncRecord(message.getMessage());
	}

	@Test
	public void testSaltedPrivateMessageFitsIntoRecord() throws Exception {

		GroupId groupId = new GroupId(getRandomId());
		long timestamp = Long.MAX_VALUE;
		String text = getRandomString(MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH);

		List<AttachmentHeader> headers = new ArrayList<>();
		for (int i = 0; i < MAX_ATTACHMENTS_PER_MESSAGE; i++) {
			headers.add(new AttachmentHeader(groupId,
					new MessageId(getRandomId()),
					getRandomString(MAX_CONTENT_TYPE_BYTES)));
		}
		PrivateMessage message = privateMessageFactory
				.createSaltedPrivateMessage(groupId, timestamp, text, headers,
						MAX_AUTO_DELETE_TIMER_MS, new MessageId(getRandomId()));

		int length = message.getMessage().getRawLength();
		assertTrue(length > UniqueId.LENGTH + 8
				+ MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH + MAX_ATTACHMENTS_PER_MESSAGE
				* (UniqueId.LENGTH + MAX_CONTENT_TYPE_BYTES) + 4
				+ UniqueId.LENGTH + BODY_SALT_LENGTH);
		assertFitsOneSyncRecord(message.getMessage());
	}

	@Test
	public void theLargestTextFitsOneSyncRecordAndOneMoreByteIsRefused()
			throws Exception {
		GroupId groupId = new GroupId(getRandomId());
		List<AttachmentHeader> headers = new ArrayList<>();
		for (int i = 0; i < MAX_ATTACHMENTS_PER_MESSAGE; i++) {
			headers.add(new AttachmentHeader(groupId,
					new MessageId(getRandomId()),
					getRandomString(MAX_CONTENT_TYPE_BYTES)));
		}
		String longest = getRandomString(
				MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH);
		String tooLong = longest + "a";
		MessageId quoted = new MessageId(getRandomId());
		assertFitsOneSyncRecord(privateMessageFactory
				.createSaltedPrivateMessage(groupId, Long.MAX_VALUE, longest,
						headers, MAX_AUTO_DELETE_TIMER_MS, quoted)
				.getMessage());
		try {
			privateMessageFactory.createLegacyPrivateMessage(groupId, 1L,
					tooLong);
			fail();
		} catch (IllegalArgumentException expected) {
		}
		try {
			privateMessageFactory.createPrivateMessage(groupId, 1L, tooLong,
					headers);
			fail();
		} catch (IllegalArgumentException expected) {
		}
		try {
			privateMessageFactory.createPrivateMessage(groupId, 1L, tooLong,
					headers, MAX_AUTO_DELETE_TIMER_MS, quoted);
			fail();
		} catch (IllegalArgumentException expected) {
		}
		try {
			privateMessageFactory.createSaltedPrivateMessage(groupId, 1L,
					tooLong, headers, MAX_AUTO_DELETE_TIMER_MS, quoted);
			fail();
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void theLargestGroupPostAndItsRelayFitOneSyncRecord()
			throws Exception {
		byte[] groupId = getRandomId();
		StringBuilder name = new StringBuilder();
		while (name.length() < 256) name.append('n');
		BdfList post = BdfList.of(32L, groupId, (long) Integer.MAX_VALUE,
				getRandomId(), name.toString(),
				new byte[GroupTrManager.MAX_POST_BODY_LENGTH], new byte[4096],
				Long.MAX_VALUE);
		BdfList unreachable = new BdfList();
		for (int i = 0; i < 254; i++) unreachable.add(getRandomId());
		BdfList relay = BdfList.of((long) MessageTypes.GROUP_RELAY_REQUEST,
				groupId, unreachable, post);
		assertTrue(clientHelper.toByteArray(post).length
				<= MAX_DELIVERABLE_MESSAGE_BODY_LENGTH
				- GroupTrManager.RELAY_ENVELOPE_BYTES);
		assertTrue(clientHelper.toByteArray(relay).length
				- clientHelper.toByteArray(post).length
				<= GroupTrManager.RELAY_ENVELOPE_BYTES);
		GroupId contactGroup = new GroupId(getRandomId());
		assertFitsOneSyncRecord(clientHelper.createMessage(contactGroup,
				Long.MAX_VALUE, post));
		assertFitsOneSyncRecord(clientHelper.createMessage(contactGroup,
				Long.MAX_VALUE, relay));
	}

	private void assertFitsOneSyncRecord(Message m) throws Exception {
		assertTrue(m.getRawLength() - MESSAGE_HEADER_LENGTH
				<= MAX_DELIVERABLE_MESSAGE_BODY_LENGTH);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SyncRecordWriter w =
				syncRecordWriterFactory.createRecordWriter(out, false);
		w.writeMessage(m);
		w.flush();
		assertTrue(out.size() <= MAX_SYNC_RECORD_BYTES);
	}

	@Test
	public void testSaltAddsThirtyFiveBytesOrThirtyFourWithAQuote()
			throws Exception {
		GroupId groupId = new GroupId(getRandomId());
		MessageId quoted = new MessageId(getRandomId());
		List<AttachmentHeader> none = new ArrayList<>();

		int plain = privateMessageFactory.createPrivateMessage(groupId, 1000L,
				"hi", none, MAX_AUTO_DELETE_TIMER_MS, null).getMessage()
				.getRawLength();
		int salted = privateMessageFactory.createSaltedPrivateMessage(groupId,
				1000L, "hi", none, MAX_AUTO_DELETE_TIMER_MS, null)
				.getMessage().getRawLength();
		int plainReply = privateMessageFactory.createPrivateMessage(groupId,
				1000L, "hi", none, MAX_AUTO_DELETE_TIMER_MS, quoted)
				.getMessage().getRawLength();
		int saltedReply = privateMessageFactory.createSaltedPrivateMessage(
				groupId, 1000L, "hi", none, MAX_AUTO_DELETE_TIMER_MS, quoted)
				.getMessage().getRawLength();

		assertEquals(35, salted - plain);
		assertEquals(34, saltedReply - plainReply);
	}

}

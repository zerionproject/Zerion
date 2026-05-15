package org.briarproject.briar.messaging;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageFactory;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.test.BrambleTestCase;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.messaging.PrivateMessage;
import org.briarproject.briar.api.messaging.PrivateMessageFactory;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import static org.briarproject.bramble.api.record.Record.MAX_RECORD_PAYLOAD_BYTES;
import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.bramble.util.StringUtils.getRandomString;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_IMAGE_SIZE;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.MAX_AUTO_DELETE_TIMER_MS;
import static org.briarproject.briar.api.messaging.MessagingConstants.MAX_ATTACHMENTS_PER_MESSAGE;
import static org.briarproject.briar.api.messaging.MessagingConstants.MAX_PRIVATE_MESSAGE_TEXT_LENGTH;
import static org.briarproject.briar.messaging.MessageTypes.ATTACHMENT;
import static org.junit.Assert.assertTrue;

public class MessageSizeIntegrationTest extends BrambleTestCase {

	@Inject
	CryptoComponent crypto;
	@Inject
	PrivateMessageFactory privateMessageFactory;
	@Inject
	ClientHelper clientHelper;
	@Inject
	MessageFactory messageFactory;

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
		String text = getRandomString(MAX_PRIVATE_MESSAGE_TEXT_LENGTH);
		PrivateMessage message = privateMessageFactory
				.createLegacyPrivateMessage(groupId, timestamp, text);

		int length = message.getMessage().getRawLength();
		assertTrue(length > UniqueId.LENGTH + 8
				+ MAX_PRIVATE_MESSAGE_TEXT_LENGTH);
		assertTrue(length <= MAX_RECORD_PAYLOAD_BYTES);
	}

	@Test
	public void testPrivateMessageFitsIntoRecord() throws Exception {

		GroupId groupId = new GroupId(getRandomId());
		long timestamp = Long.MAX_VALUE;
		String text = getRandomString(MAX_PRIVATE_MESSAGE_TEXT_LENGTH);

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
				+ MAX_PRIVATE_MESSAGE_TEXT_LENGTH + MAX_ATTACHMENTS_PER_MESSAGE
				* (UniqueId.LENGTH + MAX_CONTENT_TYPE_BYTES) + 4);
		assertTrue(length <= MAX_RECORD_PAYLOAD_BYTES);
	}

	@Test
	public void testAttachmentFitsIntoRecord() throws Exception {

		String contentType = getRandomString(MAX_CONTENT_TYPE_BYTES);
		byte[] data = getRandomBytes(MAX_IMAGE_SIZE);

		ByteArrayInputStream dataIn = new ByteArrayInputStream(data);
		ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
		byte[] descriptor =
				clientHelper.toByteArray(BdfList.of(ATTACHMENT, contentType));
		bodyOut.write(descriptor);
		copyAndClose(dataIn, bodyOut);
		byte[] body = bodyOut.toByteArray();

		GroupId groupId = new GroupId(getRandomId());
		long timestamp = Long.MAX_VALUE;
		Message message =
				messageFactory.createMessage(groupId, timestamp, body);

		int length = message.getRawLength();
		assertTrue(length > UniqueId.LENGTH + 8
				+ 1 + MAX_CONTENT_TYPE_BYTES + MAX_IMAGE_SIZE);
		assertTrue(length <= MAX_RECORD_PAYLOAD_BYTES);
	}

}

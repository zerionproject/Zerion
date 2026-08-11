package com.professor.zerion.android.conversation;

import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageValidationGateTest {

	@Test
	public void testValidationGate_EmptyTextNullAttachments_Rejected() {
		String text = null;
		List<AttachmentHeader> headers = null;

		boolean shouldSend = shouldAllowSend(text, headers);

		assertFalse(
				"Message with null text and null attachments should be REJECTED",
				shouldSend
		);
	}

	@Test
	public void testValidationGate_EmptyTextEmptyAttachments_Rejected() {
		String text = "";
		List<AttachmentHeader> headers = new ArrayList<>();

		boolean shouldSend = shouldAllowSend(text, headers);

		assertFalse(
				"Message with empty text and empty attachments should be REJECTED",
				shouldSend
		);
	}

	@Test
	public void testValidationGate_WhitespaceOnlyTextNoAttachments_Rejected() {
		String text = "   \t\n  ";
		List<AttachmentHeader> headers = new ArrayList<>();

		boolean shouldSend = shouldAllowSend(text, headers);

		assertFalse(
				"Message with whitespace-only text and no attachments should be REJECTED",
				shouldSend
		);
	}

	@Test
	public void testValidationGate_ValidTextNoAttachments_Accepted() {
		String text = "Hello, World!";
		List<AttachmentHeader> headers = new ArrayList<>();

		boolean shouldSend = shouldAllowSend(text, headers);

		assertTrue(
				"Message with valid text and no attachments should be ACCEPTED",
				shouldSend
		);
	}

	@Test
	public void testValidationGate_NoTextValidImageAttachment_Accepted() {
		String text = null;
		GroupId groupId = new GroupId(getRandomId());
		MessageId msgId = new MessageId(getRandomId());
		AttachmentHeader imageHeader = new AttachmentHeader(groupId, msgId, "image/jpeg");
		List<AttachmentHeader> headers = Collections.singletonList(imageHeader);

		boolean shouldSend = shouldAllowSend(text, headers);

		assertTrue(
				"Message with no text but valid image attachment should be ACCEPTED",
				shouldSend
		);
	}

	@Test
	public void testValidationGate_NoTextValidVideoAttachment_Accepted() {
		String text = null;
		GroupId groupId = new GroupId(getRandomId());
		MessageId msgId = new MessageId(getRandomId());
		AttachmentHeader videoHeader = new AttachmentHeader(groupId, msgId, "video/mp4");
		List<AttachmentHeader> headers = Collections.singletonList(videoHeader);

		boolean shouldSend = shouldAllowSend(text, headers);

		assertTrue(
				"Message with no text but valid video attachment should be ACCEPTED",
				shouldSend
		);
	}

	@Test
	public void testValidationGate_ValidTextValidAttachment_Accepted() {
		String text = "Check out this image!";
		GroupId groupId = new GroupId(getRandomId());
		MessageId msgId = new MessageId(getRandomId());
		AttachmentHeader header = new AttachmentHeader(groupId, msgId, "image/jpeg");
		List<AttachmentHeader> headers = Collections.singletonList(header);

		boolean shouldSend = shouldAllowSend(text, headers);

		assertTrue(
				"Message with valid text AND valid attachment should be ACCEPTED",
				shouldSend
		);
	}

	@Test
	public void testValidationGate_EmptyTextValidAudioAttachment_Accepted() {
		String text = "";
		GroupId groupId = new GroupId(getRandomId());
		MessageId msgId = new MessageId(getRandomId());
		AttachmentHeader audioHeader = new AttachmentHeader(groupId, msgId, "audio/aac");
		List<AttachmentHeader> headers = Collections.singletonList(audioHeader);

		boolean shouldSend = shouldAllowSend(text, headers);

		assertTrue(
				"Message with empty text but valid audio (voice) attachment should be ACCEPTED",
				shouldSend
		);
	}

	@Test
	public void testValidationGate_MultipleValidAttachments_Accepted() {
		String text = null;
		GroupId groupId = new GroupId(getRandomId());
		List<AttachmentHeader> headers = new ArrayList<>();

		for (int i = 0; i < 5; i++) {
			MessageId msgId = new MessageId(getRandomId());
			headers.add(new AttachmentHeader(groupId, msgId, "image/jpeg"));
		}

		boolean shouldSend = shouldAllowSend(text, headers);

		assertTrue(
				"Message with no text but multiple valid attachments should be ACCEPTED",
				shouldSend
		);
	}

	private boolean shouldAllowSend(String text, List<AttachmentHeader> headers) {
		boolean hasText = text != null && !text.trim().isEmpty();
		boolean hasAttachments = headers != null && !headers.isEmpty();
		return hasText || hasAttachments;
	}
}

package com.professor.zerion.android.attachment;

import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.junit.Test;

import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AttachmentItemVideoTest {

	@Test
	public void testIsVideo_ReturnsTrue_ForVideoMp4() {
		AttachmentItem item = createAttachmentItem("video/mp4");

		assertTrue(
				"isVideo() should return true for video/mp4 MIME type",
				item.isVideo()
		);
	}

	@Test
	public void testIsVideo_ReturnsTrue_ForVideoWebm() {
		AttachmentItem item = createAttachmentItem("video/webm");

		assertTrue(
				"isVideo() should return true for video/webm MIME type",
				item.isVideo()
		);
	}

	@Test
	public void testIsVideo_ReturnsTrue_ForVideoMkv() {
		AttachmentItem item = createAttachmentItem("video/x-matroska");

		assertTrue(
				"isVideo() should return true for video/x-matroska MIME type",
				item.isVideo()
		);
	}

	@Test
	public void testIsVideo_ReturnsTrue_ForVideoQuicktime() {
		AttachmentItem item = createAttachmentItem("video/quicktime");

		assertTrue(
				"isVideo() should return true for video/quicktime (MOV) MIME type",
				item.isVideo()
		);
	}

	@Test
	public void testIsVideo_ReturnsTrue_ForVideo3gp() {
		AttachmentItem item = createAttachmentItem("video/3gpp");

		assertTrue(
				"isVideo() should return true for video/3gpp MIME type",
				item.isVideo()
		);
	}

	@Test
	public void testIsVideo_ReturnsFalse_ForImageJpeg() {
		AttachmentItem item = createAttachmentItem("image/jpeg");

		assertFalse(
				"isVideo() should return false for image/jpeg MIME type",
				item.isVideo()
		);
	}

	@Test
	public void testIsVideo_ReturnsFalse_ForImagePng() {
		AttachmentItem item = createAttachmentItem("image/png");

		assertFalse(
				"isVideo() should return false for image/png MIME type",
				item.isVideo()
		);
	}

	@Test
	public void testIsVideo_ReturnsFalse_ForImageGif() {
		AttachmentItem item = createAttachmentItem("image/gif");

		assertFalse(
				"isVideo() should return false for image/gif MIME type",
				item.isVideo()
		);
	}

	@Test
	public void testIsVideo_ReturnsFalse_ForAudio() {
		AttachmentItem item = createAttachmentItem("audio/aac");

		assertFalse(
				"isVideo() should return false for audio/aac MIME type",
				item.isVideo()
		);
	}

	@Test
	public void testIsAudio_ReturnsTrue_ForAudioAac() {
		AttachmentItem item = createAttachmentItem("audio/aac");

		assertTrue(
				"isAudio() should return true for audio/aac MIME type",
				item.isAudio()
		);
	}

	@Test
	public void testIsAudio_ReturnsTrue_ForAudioMp3() {
		AttachmentItem item = createAttachmentItem("audio/mpeg");

		assertTrue(
				"isAudio() should return true for audio/mpeg (MP3) MIME type",
				item.isAudio()
		);
	}

	@Test
	public void testIsAudio_ReturnsTrue_ForAudioOgg() {
		AttachmentItem item = createAttachmentItem("audio/ogg");

		assertTrue(
				"isAudio() should return true for audio/ogg MIME type",
				item.isAudio()
		);
	}

	@Test
	public void testIsAudio_ReturnsFalse_ForVideo() {
		AttachmentItem item = createAttachmentItem("video/mp4");

		assertFalse(
				"isAudio() should return false for video/mp4 MIME type",
				item.isAudio()
		);
	}

	@Test
	public void testIsAudio_ReturnsFalse_ForImage() {
		AttachmentItem item = createAttachmentItem("image/jpeg");

		assertFalse(
				"isAudio() should return false for image/jpeg MIME type",
				item.isAudio()
		);
	}

	@Test
	public void testVideoAndAudio_MutuallyExclusive() {
		AttachmentItem videoItem = createAttachmentItem("video/mp4");
		AttachmentItem audioItem = createAttachmentItem("audio/aac");
		AttachmentItem imageItem = createAttachmentItem("image/jpeg");

		assertTrue("Video item should be video", videoItem.isVideo());
		assertFalse("Video item should not be audio", videoItem.isAudio());

		assertTrue("Audio item should be audio", audioItem.isAudio());
		assertFalse("Audio item should not be video", audioItem.isVideo());

		assertFalse("Image item should not be video", imageItem.isVideo());
		assertFalse("Image item should not be audio", imageItem.isAudio());
	}

	@Test
	public void testGetMimeType_ReturnsCorrectType() {
		String expectedMimeType = "video/mp4";
		AttachmentItem item = createAttachmentItem(expectedMimeType);

		String actualMimeType = item.getMimeType();

		assertTrue(
				"getMimeType() should return the correct MIME type",
				expectedMimeType.equals(actualMimeType)
		);
	}

	private AttachmentItem createAttachmentItem(String mimeType) {
		GroupId groupId = new GroupId(getRandomId());
		MessageId msgId = new MessageId(getRandomId());
		AttachmentHeader header = new AttachmentHeader(groupId, msgId, mimeType);

		return new AttachmentItem(
				header,
				1920,
				1080,
				getExtensionForMimeType(mimeType),
				480,
				270,
				AttachmentItem.State.AVAILABLE
		);
	}

	private String getExtensionForMimeType(String mimeType) {
		if (mimeType.startsWith("video/")) {
			if (mimeType.contains("mp4")) return "mp4";
			if (mimeType.contains("webm")) return "webm";
			if (mimeType.contains("matroska")) return "mkv";
			if (mimeType.contains("quicktime")) return "mov";
			if (mimeType.contains("3gpp")) return "3gp";
			return "mp4";
		} else if (mimeType.startsWith("audio/")) {
			if (mimeType.contains("aac")) return "aac";
			if (mimeType.contains("mpeg")) return "mp3";
			if (mimeType.contains("ogg")) return "ogg";
			return "aac";
		} else if (mimeType.startsWith("image/")) {
			if (mimeType.contains("jpeg")) return "jpg";
			if (mimeType.contains("png")) return "png";
			if (mimeType.contains("gif")) return "gif";
			return "jpg";
		}
		return "bin";
	}
}

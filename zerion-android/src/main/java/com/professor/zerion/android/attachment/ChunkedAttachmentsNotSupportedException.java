package com.professor.zerion.android.attachment;

/**
 * Exception thrown when attempting to send video/audio attachments to a contact
 * that doesn't support chunked attachments (Briar clients or older Zerion versions).
 */
public class ChunkedAttachmentsNotSupportedException extends Exception {

	private final String contentType;

	public ChunkedAttachmentsNotSupportedException(String contentType) {
		super("Contact does not support chunked attachments for: " + contentType);
		this.contentType = contentType;
	}

	public String getContentType() {
		return contentType;
	}

	public boolean isVideo() {
		return contentType != null && contentType.startsWith("video/");
	}

	public boolean isAudio() {
		return contentType != null && contentType.startsWith("audio/");
	}
}

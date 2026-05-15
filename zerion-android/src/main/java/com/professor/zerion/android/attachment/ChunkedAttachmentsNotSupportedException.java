package com.professor.zerion.android.attachment;

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

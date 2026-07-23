package org.zerionproject.app.api.messaging;

public enum PrivateMessageFormat {

	TEXT_ONLY,

	TEXT_IMAGES,

	TEXT_IMAGES_AUTO_DELETE,

	TEXT_IMAGES_CHUNKED;

	public boolean supportsImages() {
		return this != TEXT_ONLY;
	}

	public boolean supportsChunkedAttachments() {
		return this == TEXT_IMAGES_CHUNKED;
	}
}

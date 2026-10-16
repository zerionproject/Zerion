package org.zerionproject.app.api.messaging;

public enum PrivateMessageFormat {

	TEXT_ONLY,

	TEXT_IMAGES,

	TEXT_IMAGES_AUTO_DELETE,

	TEXT_IMAGES_CHUNKED,

	TEXT_IMAGES_SALTED;

	public boolean supportsImages() {
		return this != TEXT_ONLY;
	}

	public boolean supportsChunkedAttachments() {
		return this == TEXT_IMAGES_CHUNKED || this == TEXT_IMAGES_SALTED;
	}

	public boolean isSalted() {
		return this == TEXT_IMAGES_SALTED;
	}
}

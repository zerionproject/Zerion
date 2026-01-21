package org.briarproject.briar.api.messaging;

public enum PrivateMessageFormat {

	/**
	 * First version of the private message format, which doesn't support
	 * image attachments or auto-deletion.
	 */
	TEXT_ONLY,

	/**
	 * Second version of the private message format, which supports image
	 * attachments but not auto-deletion. Support for this format was
	 * added in client version 0.1.
	 */
	TEXT_IMAGES,

	/**
	 * Third version of the private message format, which supports image
	 * attachments and auto-deletion. Support for this format was added
	 * in client version 0.3.
	 */
	TEXT_IMAGES_AUTO_DELETE,

	/**
	 * Fourth version of the private message format, which adds support for
	 * chunked attachments (video/audio files up to 10MB). Support for this
	 * format was added in client version 0.4 (Zerion only).
	 */
	TEXT_IMAGES_CHUNKED;

	/**
	 * Returns true if this format supports image attachments.
	 */
	public boolean supportsImages() {
		return this != TEXT_ONLY;
	}

	/**
	 * Returns true if this format supports chunked attachments (video/audio).
	 * Only Zerion clients with version 0.4+ support this feature.
	 * Briar clients and older Zerion clients will reject these message types.
	 */
	public boolean supportsChunkedAttachments() {
		return this == TEXT_IMAGES_CHUNKED;
	}
}

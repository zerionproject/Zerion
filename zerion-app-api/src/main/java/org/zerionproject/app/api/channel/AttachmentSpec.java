package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class AttachmentSpec {

	private final String mimeType;
	private final byte[] plaintextBytes;
	@Nullable
	private final String captionUtf8;
	@Nullable
	private final byte[] plaintextThumbnail;

	public AttachmentSpec(String mimeType, byte[] plaintextBytes,
			@Nullable String captionUtf8) {
		this(mimeType, plaintextBytes, captionUtf8, null);
	}

	public AttachmentSpec(String mimeType, byte[] plaintextBytes,
			@Nullable String captionUtf8,
			@Nullable byte[] plaintextThumbnail) {
		this.mimeType = mimeType;
		this.plaintextBytes = plaintextBytes;
		this.captionUtf8 = captionUtf8;
		this.plaintextThumbnail = plaintextThumbnail;
	}

	public String getMimeType() {
		return mimeType;
	}

	public byte[] getPlaintextBytes() {
		return plaintextBytes;
	}

	@Nullable
	public String getCaptionUtf8() {
		return captionUtf8;
	}

	@Nullable
	public byte[] getPlaintextThumbnail() {
		return plaintextThumbnail;
	}
}

package org.briarproject.briar.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class AttachmentSpec {

	private final String mimeType;
	private final byte[] plaintextBytes;
	@Nullable
	private final String captionUtf8;

	public AttachmentSpec(String mimeType, byte[] plaintextBytes,
			@Nullable String captionUtf8) {
		this.mimeType = mimeType;
		this.plaintextBytes = plaintextBytes;
		this.captionUtf8 = captionUtf8;
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
}

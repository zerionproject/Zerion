package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class AttachmentBlob {

	private final byte[] plaintextBytes;
	private final String mimeType;

	public AttachmentBlob(byte[] plaintextBytes, String mimeType) {
		this.plaintextBytes = plaintextBytes;
		this.mimeType = mimeType;
	}

	public byte[] getPlaintextBytes() {
		return plaintextBytes;
	}

	public String getMimeType() {
		return mimeType;
	}
}

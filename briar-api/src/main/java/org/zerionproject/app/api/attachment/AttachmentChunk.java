package org.zerionproject.app.api.attachment;

import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class AttachmentChunk {

	private final MessageId attachmentId;
	private final int chunkIndex;
	private final byte[] data;
	private final byte[] chunkHash;

	public AttachmentChunk(MessageId attachmentId, int chunkIndex,
			byte[] data, byte[] chunkHash) {
		this.attachmentId = attachmentId;
		this.chunkIndex = chunkIndex;
		this.data = data;
		this.chunkHash = chunkHash;
	}

	public MessageId getAttachmentId() {
		return attachmentId;
	}

	public int getChunkIndex() {
		return chunkIndex;
	}

	public byte[] getData() {
		return data;
	}

	public byte[] getChunkHash() {
		return chunkHash;
	}
}

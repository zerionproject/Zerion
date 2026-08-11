package org.zerionproject.app.api.attachment;

import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class AttachmentManifest {

	public static final int CURRENT_VERSION = 1;

	private final int version;
	private final MessageId attachmentId;
	private final String contentType;
	private final long totalSize;
	private final int chunkCount;
	private final int chunkSize;
	private final byte[] rootHash;
	private final byte[] manifestMac;

	public AttachmentManifest(MessageId attachmentId, String contentType,
			long totalSize, int chunkCount, int chunkSize, byte[] rootHash,
			byte[] manifestMac) {
		this(CURRENT_VERSION, attachmentId, contentType, totalSize, chunkCount,
				chunkSize, rootHash, manifestMac);
	}

	public AttachmentManifest(int version, MessageId attachmentId, String contentType,
			long totalSize, int chunkCount, int chunkSize, byte[] rootHash,
			byte[] manifestMac) {
		this.version = version;
		this.attachmentId = attachmentId;
		this.contentType = contentType;
		this.totalSize = totalSize;
		this.chunkCount = chunkCount;
		this.chunkSize = chunkSize;
		this.rootHash = rootHash;
		this.manifestMac = manifestMac;
	}

	public int getVersion() {
		return version;
	}

	public MessageId getAttachmentId() {
		return attachmentId;
	}

	public String getContentType() {
		return contentType;
	}

	public long getTotalSize() {
		return totalSize;
	}

	public int getChunkCount() {
		return chunkCount;
	}

	public int getChunkSize() {
		return chunkSize;
	}

	public byte[] getRootHash() {
		return rootHash;
	}

	public byte[] getManifestMac() {
		return manifestMac;
	}
}

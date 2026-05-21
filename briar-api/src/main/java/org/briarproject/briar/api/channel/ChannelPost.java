package org.briarproject.briar.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
public class ChannelPost {

	private final byte[] channelId;
	private final long seqNum;
	private final byte[] prevHash;
	private final long timestampHourMs;
	private final String body;
	private final List<ChannelAttachment> attachments;
	private final long ttlMs;
	private final byte[] signature;
	private final boolean read;

	public ChannelPost(byte[] channelId, long seqNum, byte[] prevHash,
			long timestampHourMs, String body,
			List<ChannelAttachment> attachments, long ttlMs,
			byte[] signature, boolean read) {
		this.channelId = channelId;
		this.seqNum = seqNum;
		this.prevHash = prevHash;
		this.timestampHourMs = timestampHourMs;
		this.body = body;
		this.attachments = Collections.unmodifiableList(attachments);
		this.ttlMs = ttlMs;
		this.signature = signature;
		this.read = read;
	}

	public byte[] getChannelId() {
		return channelId;
	}

	public long getSeqNum() {
		return seqNum;
	}

	public byte[] getPrevHash() {
		return prevHash;
	}

	public long getTimestampHourMs() {
		return timestampHourMs;
	}

	public String getBody() {
		return body;
	}

	public List<ChannelAttachment> getAttachments() {
		return attachments;
	}

	public long getTtlMs() {
		return ttlMs;
	}

	public byte[] getSignature() {
		return signature;
	}

	public boolean isRead() {
		return read;
	}

	public boolean isEphemeral() {
		return ttlMs > 0;
	}

	@NotNullByDefault
	public static final class ChannelAttachment {

		private final byte[] blobHash;
		private final long sizeBytes;
		private final String mimeType;
		private final byte[] perAttachmentKey;
		@Nullable
		private final String captionUtf8;

		public ChannelAttachment(byte[] blobHash, long sizeBytes,
				String mimeType, byte[] perAttachmentKey,
				@Nullable String captionUtf8) {
			this.blobHash = blobHash;
			this.sizeBytes = sizeBytes;
			this.mimeType = mimeType;
			this.perAttachmentKey = perAttachmentKey;
			this.captionUtf8 = captionUtf8;
		}

		public byte[] getBlobHash() {
			return blobHash;
		}

		public long getSizeBytes() {
			return sizeBytes;
		}

		public String getMimeType() {
			return mimeType;
		}

		public byte[] getPerAttachmentKey() {
			return perAttachmentKey;
		}

		@Nullable
		public String getCaptionUtf8() {
			return captionUtf8;
		}
	}
}

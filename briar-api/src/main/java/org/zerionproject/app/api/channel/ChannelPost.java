package org.zerionproject.app.api.channel;

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
	@Nullable
	private final byte[] delegateSignerEd25519PubKey;
	@Nullable
	private final byte[] delegateSignerMlDsaPubKey;

	public ChannelPost(byte[] channelId, long seqNum, byte[] prevHash,
			long timestampHourMs, String body,
			List<ChannelAttachment> attachments, long ttlMs,
			byte[] signature, boolean read) {
		this(channelId, seqNum, prevHash, timestampHourMs, body,
				attachments, ttlMs, signature, read, null, null);
	}

	public ChannelPost(byte[] channelId, long seqNum, byte[] prevHash,
			long timestampHourMs, String body,
			List<ChannelAttachment> attachments, long ttlMs,
			byte[] signature, boolean read,
			@Nullable byte[] delegateSignerEd25519PubKey,
			@Nullable byte[] delegateSignerMlDsaPubKey) {
		this.channelId = channelId;
		this.seqNum = seqNum;
		this.prevHash = prevHash;
		this.timestampHourMs = timestampHourMs;
		this.body = body;
		this.attachments = Collections.unmodifiableList(attachments);
		this.ttlMs = ttlMs;
		this.signature = signature;
		this.read = read;
		this.delegateSignerEd25519PubKey = delegateSignerEd25519PubKey;
		this.delegateSignerMlDsaPubKey = delegateSignerMlDsaPubKey;
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

	@Nullable
	public byte[] getDelegateSignerEd25519PubKey() {
		return delegateSignerEd25519PubKey;
	}

	@Nullable
	public byte[] getDelegateSignerMlDsaPubKey() {
		return delegateSignerMlDsaPubKey;
	}

	public boolean signedByDelegate() {
		return delegateSignerEd25519PubKey != null;
	}

	@NotNullByDefault
	public static final class ChannelAttachment {

		private final byte[] blobHash;
		private final long sizeBytes;
		private final String mimeType;
		private final byte[] perAttachmentKey;
		@Nullable
		private final String captionUtf8;
		@Nullable
		private final byte[] thumbnail;

		public ChannelAttachment(byte[] blobHash, long sizeBytes,
				String mimeType, byte[] perAttachmentKey,
				@Nullable String captionUtf8) {
			this(blobHash, sizeBytes, mimeType, perAttachmentKey,
					captionUtf8, null);
		}

		public ChannelAttachment(byte[] blobHash, long sizeBytes,
				String mimeType, byte[] perAttachmentKey,
				@Nullable String captionUtf8,
				@Nullable byte[] thumbnail) {
			this.blobHash = blobHash;
			this.sizeBytes = sizeBytes;
			this.mimeType = mimeType;
			this.perAttachmentKey = perAttachmentKey;
			this.captionUtf8 = captionUtf8;
			this.thumbnail = thumbnail;
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

		@Nullable
		public byte[] getThumbnail() {
			return thumbnail;
		}
	}
}

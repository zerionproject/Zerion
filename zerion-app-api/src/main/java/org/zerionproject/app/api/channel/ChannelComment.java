package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class ChannelComment {

	private final long parentPostSeqNum;
	private final long commentId;
	private final String body;
	private final String authorDisplayName;
	private final byte[] authorEd25519PubKey;
	private final byte[] authorMlDsaPubKey;
	private final long timestampHourMs;
	private final byte[] signature;

	public ChannelComment(long parentPostSeqNum, long commentId,
			String body, String authorDisplayName,
			byte[] authorEd25519PubKey, byte[] authorMlDsaPubKey,
			long timestampHourMs) {
		this(parentPostSeqNum, commentId, body, authorDisplayName,
				authorEd25519PubKey, authorMlDsaPubKey, timestampHourMs,
				new byte[0]);
	}

	public ChannelComment(long parentPostSeqNum, long commentId,
			String body, String authorDisplayName,
			byte[] authorEd25519PubKey, byte[] authorMlDsaPubKey,
			long timestampHourMs, byte[] signature) {
		this.parentPostSeqNum = parentPostSeqNum;
		this.commentId = commentId;
		this.body = body;
		this.authorDisplayName = authorDisplayName;
		this.authorEd25519PubKey = authorEd25519PubKey;
		this.authorMlDsaPubKey = authorMlDsaPubKey;
		this.timestampHourMs = timestampHourMs;
		this.signature = signature;
	}

	public byte[] getSignature() {
		return signature;
	}

	public long getParentPostSeqNum() {
		return parentPostSeqNum;
	}

	public long getCommentId() {
		return commentId;
	}

	public String getBody() {
		return body;
	}

	public String getAuthorDisplayName() {
		return authorDisplayName;
	}

	public byte[] getAuthorEd25519PubKey() {
		return authorEd25519PubKey;
	}

	public byte[] getAuthorMlDsaPubKey() {
		return authorMlDsaPubKey;
	}

	public long getTimestampHourMs() {
		return timestampHourMs;
	}
}

package org.briarproject.briar.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class ChannelReaction {

	private final long postSeqNum;
	private final String emoji;
	private final byte[] signerEd25519PubKey;
	private final byte[] signerMlDsaPubKey;
	private final long timestampHourMs;

	public ChannelReaction(long postSeqNum, String emoji,
			byte[] signerEd25519PubKey, byte[] signerMlDsaPubKey,
			long timestampHourMs) {
		this.postSeqNum = postSeqNum;
		this.emoji = emoji;
		this.signerEd25519PubKey = signerEd25519PubKey;
		this.signerMlDsaPubKey = signerMlDsaPubKey;
		this.timestampHourMs = timestampHourMs;
	}

	public long getPostSeqNum() {
		return postSeqNum;
	}

	public String getEmoji() {
		return emoji;
	}

	public byte[] getSignerEd25519PubKey() {
		return signerEd25519PubKey;
	}

	public byte[] getSignerMlDsaPubKey() {
		return signerMlDsaPubKey;
	}

	public long getTimestampHourMs() {
		return timestampHourMs;
	}
}

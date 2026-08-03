package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class ChannelReaction {

	private final long postSeqNum;
	private final String emoji;
	private final byte[] signerEd25519PubKey;
	private final byte[] signerMlDsaPubKey;
	private final long timestampHourMs;
	private final byte[] signature;

	public ChannelReaction(long postSeqNum, String emoji,
			byte[] signerEd25519PubKey, byte[] signerMlDsaPubKey,
			long timestampHourMs) {
		this(postSeqNum, emoji, signerEd25519PubKey, signerMlDsaPubKey,
				timestampHourMs, new byte[0]);
	}

	public ChannelReaction(long postSeqNum, String emoji,
			byte[] signerEd25519PubKey, byte[] signerMlDsaPubKey,
			long timestampHourMs, byte[] signature) {
		this.postSeqNum = postSeqNum;
		this.emoji = emoji;
		this.signerEd25519PubKey = signerEd25519PubKey;
		this.signerMlDsaPubKey = signerMlDsaPubKey;
		this.timestampHourMs = timestampHourMs;
		this.signature = signature;
	}

	public byte[] getSignature() {
		return signature;
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

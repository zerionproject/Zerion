package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class ChannelDelegationCert {

	private final byte[] channelId;
	private final byte[] delegateeEd25519PubKey;
	private final byte[] delegateeMlDsaPubKey;
	private final long validFromHourMs;
	private final long validUntilHourMs;
	private final long delegationSeq;
	private final byte[] signature;

	public ChannelDelegationCert(byte[] channelId,
			byte[] delegateeEd25519PubKey, byte[] delegateeMlDsaPubKey,
			long validFromHourMs, long validUntilHourMs,
			long delegationSeq, byte[] signature) {
		this.channelId = channelId;
		this.delegateeEd25519PubKey = delegateeEd25519PubKey;
		this.delegateeMlDsaPubKey = delegateeMlDsaPubKey;
		this.validFromHourMs = validFromHourMs;
		this.validUntilHourMs = validUntilHourMs;
		this.delegationSeq = delegationSeq;
		this.signature = signature;
	}

	public byte[] getChannelId() {
		return channelId;
	}

	public byte[] getDelegateeEd25519PubKey() {
		return delegateeEd25519PubKey;
	}

	public byte[] getDelegateeMlDsaPubKey() {
		return delegateeMlDsaPubKey;
	}

	public long getValidFromHourMs() {
		return validFromHourMs;
	}

	public long getValidUntilHourMs() {
		return validUntilHourMs;
	}

	public long getDelegationSeq() {
		return delegationSeq;
	}

	public byte[] getSignature() {
		return signature;
	}

	public boolean isUnbounded() {
		return validUntilHourMs == 0L;
	}

	public boolean coversTimestamp(long timestampHourMs) {
		if (timestampHourMs < validFromHourMs) return false;
		if (validUntilHourMs == 0L) return true;
		return timestampHourMs <= validUntilHourMs;
	}
}

package org.zerionproject.core.api.db;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class PcsSessionRow {

	private final byte[] chainKey;
	private final int messageNumber;
	private final int previousChainLength;
	@Nullable
	private final byte[] rootKey;
	@Nullable
	private final byte[] dhPrivateKey;
	@Nullable
	private final byte[] dhPublicKey;
	@Nullable
	private final byte[] dhRemotePublicKey;
	private final boolean mode2Enabled;
	@Nullable
	private final byte[] mode3FullStateBlob;

	public PcsSessionRow(byte[] chainKey, int messageNumber,
			int previousChainLength, @Nullable byte[] rootKey,
			@Nullable byte[] dhPrivateKey, @Nullable byte[] dhPublicKey,
			@Nullable byte[] dhRemotePublicKey, boolean mode2Enabled,
			@Nullable byte[] mode3FullStateBlob) {
		this.chainKey = chainKey;
		this.messageNumber = messageNumber;
		this.previousChainLength = previousChainLength;
		this.rootKey = rootKey;
		this.dhPrivateKey = dhPrivateKey;
		this.dhPublicKey = dhPublicKey;
		this.dhRemotePublicKey = dhRemotePublicKey;
		this.mode2Enabled = mode2Enabled;
		this.mode3FullStateBlob = mode3FullStateBlob;
	}

	public byte[] getChainKey() {
		return chainKey;
	}

	public int getMessageNumber() {
		return messageNumber;
	}

	public int getPreviousChainLength() {
		return previousChainLength;
	}

	@Nullable
	public byte[] getRootKey() {
		return rootKey;
	}

	@Nullable
	public byte[] getDhPrivateKey() {
		return dhPrivateKey;
	}

	@Nullable
	public byte[] getDhPublicKey() {
		return dhPublicKey;
	}

	@Nullable
	public byte[] getDhRemotePublicKey() {
		return dhRemotePublicKey;
	}

	public boolean isMode2Enabled() {
		return mode2Enabled;
	}

	@Nullable
	public byte[] getMode3FullStateBlob() {
		return mode3FullStateBlob;
	}
}

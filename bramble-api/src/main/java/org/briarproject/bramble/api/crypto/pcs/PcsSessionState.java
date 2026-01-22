package org.briarproject.bramble.api.crypto.pcs;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class PcsSessionState {

	private final SecretKey chainKey;
	private final int messageNumber;
	private final int previousChainLength;

	@Nullable
	private final SecretKey rootKey;

	@Nullable
	private final DhRatchetState dhState;

	public PcsSessionState(SecretKey chainKey, int messageNumber,
			int previousChainLength) {
		this.chainKey = chainKey;
		this.messageNumber = messageNumber;
		this.previousChainLength = previousChainLength;
		this.rootKey = null;
		this.dhState = null;
	}

	public PcsSessionState(SecretKey chainKey, int messageNumber,
			int previousChainLength, @Nullable SecretKey rootKey,
			@Nullable DhRatchetState dhState) {
		this.chainKey = chainKey;
		this.messageNumber = messageNumber;
		this.previousChainLength = previousChainLength;
		this.rootKey = rootKey;
		this.dhState = dhState;
	}

	public static PcsSessionState createInitial(SecretKey rootKey) {
		return new PcsSessionState(rootKey, 0, 0);
	}

	public static PcsSessionState createInitialMode2(SecretKey rootKey,
			SecretKey chainKey, DhRatchetState dhState) {
		return new PcsSessionState(chainKey, 0, 0, rootKey, dhState);
	}

	public SecretKey getChainKey() {
		return chainKey;
	}

	public int getMessageNumber() {
		return messageNumber;
	}

	public int getPreviousChainLength() {
		return previousChainLength;
	}

	@Nullable
	public SecretKey getRootKey() {
		return rootKey;
	}

	@Nullable
	public DhRatchetState getDhState() {
		return dhState;
	}

	public boolean isMode2() {
		return dhState != null;
	}

	public PcsSessionState advance(SecretKey newChainKey) {
		return new PcsSessionState(newChainKey, messageNumber + 1,
				previousChainLength, rootKey, dhState);
	}

	public PcsSessionState newChain(SecretKey newChainKey) {
		return new PcsSessionState(newChainKey, 0, messageNumber,
				rootKey, dhState);
	}

	public PcsSessionState afterDhRatchet(SecretKey newRootKey,
			SecretKey newChainKey, DhRatchetState newDhState) {
		return new PcsSessionState(newChainKey, 0, messageNumber,
				newRootKey, newDhState);
	}

	public PcsSessionState withDhState(DhRatchetState newDhState) {
		return new PcsSessionState(chainKey, messageNumber,
				previousChainLength, rootKey, newDhState);
	}
}

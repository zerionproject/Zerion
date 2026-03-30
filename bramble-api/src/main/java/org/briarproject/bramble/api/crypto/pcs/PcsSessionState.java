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

	private final boolean mode3Enabled;
	private final long pqEpoch;

	public PcsSessionState(SecretKey chainKey, int messageNumber,
			int previousChainLength, @Nullable SecretKey rootKey,
			@Nullable DhRatchetState dhState) {
		this(chainKey, messageNumber, previousChainLength, rootKey, dhState,
				false, 0);
	}

	public PcsSessionState(SecretKey chainKey, int messageNumber,
			int previousChainLength, @Nullable SecretKey rootKey,
			@Nullable DhRatchetState dhState, boolean mode3Enabled,
			long pqEpoch) {
		this.chainKey = chainKey;
		this.messageNumber = messageNumber;
		this.previousChainLength = previousChainLength;
		this.rootKey = rootKey;
		this.dhState = dhState;
		this.mode3Enabled = mode3Enabled;
		this.pqEpoch = pqEpoch;
	}

	public static PcsSessionState createInitialMode2(SecretKey rootKey,
			SecretKey chainKey, DhRatchetState dhState) {
		return new PcsSessionState(chainKey, 0, 0, rootKey, dhState);
	}

	public static PcsSessionState createInitialMode3(SecretKey rootKey,
			SecretKey chainKey, DhRatchetState dhState) {
		return new PcsSessionState(chainKey, 0, 0, rootKey, dhState, true, 0);
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

	public boolean isMode3() {
		return dhState != null && mode3Enabled;
	}

	public long getPqEpoch() {
		return pqEpoch;
	}

	public PcsSessionState advance(SecretKey newChainKey) {
		return new PcsSessionState(newChainKey, messageNumber + 1,
				previousChainLength, rootKey, dhState, mode3Enabled, pqEpoch);
	}

	public PcsSessionState newChain(SecretKey newChainKey) {
		return new PcsSessionState(newChainKey, 0, messageNumber,
				rootKey, dhState, mode3Enabled, pqEpoch);
	}

	public PcsSessionState afterDhRatchet(SecretKey newRootKey,
			SecretKey newChainKey, DhRatchetState newDhState) {
		return new PcsSessionState(newChainKey, 0, messageNumber,
				newRootKey, newDhState, mode3Enabled, pqEpoch);
	}

	public PcsSessionState withDhState(DhRatchetState newDhState) {
		return new PcsSessionState(chainKey, messageNumber,
				previousChainLength, rootKey, newDhState, mode3Enabled, pqEpoch);
	}

	public PcsSessionState withPqEpoch(long newPqEpoch) {
		return new PcsSessionState(chainKey, messageNumber,
				previousChainLength, rootKey, dhState, mode3Enabled, newPqEpoch);
	}

	public PcsSessionState afterPqRatchet(SecretKey newRootKey, long newPqEpoch) {
		return new PcsSessionState(chainKey, messageNumber,
				previousChainLength, newRootKey, dhState, mode3Enabled, newPqEpoch);
	}

	public PcsSessionState enableMode3() {
		if (!isMode2()) {
			throw new IllegalStateException("Mode 2 required for Mode 3");
		}
		return new PcsSessionState(chainKey, messageNumber,
				previousChainLength, rootKey, dhState, true, 0);
	}
}

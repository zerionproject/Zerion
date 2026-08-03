package org.zerionproject.core.api.crypto.pcs;

import org.zerionproject.core.api.crypto.SecretKey;
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

	@Nullable
	private final Mode3FullState mode3FullState;

	public PcsSessionState(SecretKey chainKey, int messageNumber,
			int previousChainLength, @Nullable SecretKey rootKey,
			@Nullable DhRatchetState dhState) {
		this(chainKey, messageNumber, previousChainLength, rootKey, dhState,
				false, 0, null);
	}

	public PcsSessionState(SecretKey chainKey, int messageNumber,
			int previousChainLength, @Nullable SecretKey rootKey,
			@Nullable DhRatchetState dhState, boolean mode3Enabled,
			long pqEpoch) {
		this(chainKey, messageNumber, previousChainLength, rootKey, dhState,
				mode3Enabled, pqEpoch, null);
	}

	public PcsSessionState(SecretKey chainKey, int messageNumber,
			int previousChainLength, @Nullable SecretKey rootKey,
			@Nullable DhRatchetState dhState, boolean mode3Enabled,
			long pqEpoch, @Nullable Mode3FullState mode3FullState) {
		this.chainKey = chainKey;
		this.messageNumber = messageNumber;
		this.previousChainLength = previousChainLength;
		this.rootKey = rootKey;
		this.dhState = dhState;
		this.mode3Enabled = mode3Enabled;
		this.pqEpoch = pqEpoch;
		this.mode3FullState = mode3FullState;
	}

	public static PcsSessionState createInitialMode2(SecretKey rootKey,
			SecretKey chainKey, DhRatchetState dhState) {
		return new PcsSessionState(chainKey, 0, 0, rootKey, dhState);
	}

	public static PcsSessionState createInitialMode3(SecretKey rootKey,
			SecretKey chainKey, DhRatchetState dhState) {
		return new PcsSessionState(chainKey, 0, 0, rootKey, dhState, true, 0);
	}

	public static PcsSessionState createInitialMode3Full(SecretKey rootKey,
			SecretKey chainKey, DhRatchetState dhState,
			Mode3FullState mode3FullState) {
		return new PcsSessionState(chainKey, 0, 0, rootKey, dhState, true, 0,
				mode3FullState);
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

	public boolean isMode3Full() {
		return mode3FullState != null;
	}

	public long getPqEpoch() {
		return pqEpoch;
	}

	@Nullable
	public Mode3FullState getMode3FullState() {
		return mode3FullState;
	}

	public PcsSessionState advance(SecretKey newChainKey) {
		return new PcsSessionState(newChainKey, messageNumber + 1,
				previousChainLength, rootKey, dhState, mode3Enabled, pqEpoch,
				mode3FullState);
	}

	public PcsSessionState newChain(SecretKey newChainKey) {
		return new PcsSessionState(newChainKey, 0, messageNumber,
				rootKey, dhState, mode3Enabled, pqEpoch, mode3FullState);
	}

	public PcsSessionState afterDhRatchet(SecretKey newRootKey,
			SecretKey newChainKey, DhRatchetState newDhState) {
		return new PcsSessionState(newChainKey, 0, messageNumber,
				newRootKey, newDhState, mode3Enabled, pqEpoch, mode3FullState);
	}

	public PcsSessionState withDhState(DhRatchetState newDhState) {
		return new PcsSessionState(chainKey, messageNumber,
				previousChainLength, rootKey, newDhState, mode3Enabled, pqEpoch,
				mode3FullState);
	}

	public PcsSessionState withPqEpoch(long newPqEpoch) {
		return new PcsSessionState(chainKey, messageNumber,
				previousChainLength, rootKey, dhState, mode3Enabled, newPqEpoch,
				mode3FullState);
	}

	public PcsSessionState afterPqRatchet(SecretKey newRootKey, long newPqEpoch) {
		return new PcsSessionState(chainKey, messageNumber,
				previousChainLength, newRootKey, dhState, mode3Enabled,
				newPqEpoch, mode3FullState);
	}

	public PcsSessionState withMode3FullState(
			@Nullable Mode3FullState newMode3FullState) {
		return new PcsSessionState(chainKey, messageNumber,
				previousChainLength, rootKey, dhState, mode3Enabled, pqEpoch,
				newMode3FullState);
	}

	public PcsSessionState enableMode3() {
		if (!isMode2()) {
			throw new IllegalStateException("Mode 2 required for Mode 3");
		}
		return new PcsSessionState(chainKey, messageNumber,
				previousChainLength, rootKey, dhState, true, 0, mode3FullState);
	}
}

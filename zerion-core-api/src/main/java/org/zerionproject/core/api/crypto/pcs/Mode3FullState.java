package org.zerionproject.core.api.crypto.pcs;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_ENCAPSULATION_KEY_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_RECV_SK_LRU_SIZE;

@Immutable
@NotNullByDefault
public class Mode3FullState {

	@Nullable
	private final byte[] theirActivePqPk;
	private final MlKemKeyPair ourActiveKeyPair;
	private final Map<KpId, MlKemKeyPair> recentKeyPairs;
	private final long messageCounter;
	@Nullable
	private final KpId peerUsedKpId;

	public Mode3FullState(@Nullable byte[] theirActivePqPk,
			MlKemKeyPair ourActiveKeyPair,
			Map<KpId, MlKemKeyPair> recentKeyPairs, long messageCounter) {
		this(theirActivePqPk, ourActiveKeyPair, recentKeyPairs,
				messageCounter, null);
	}

	/**
	 * @param peerUsedKpId the id of our key pair that the peer's most
	 * recently opened frame encapsulated to, or null if no frame has yet
	 * carried a ciphertext. Receive-owned and transient: it is never
	 * persisted.
	 */
	public Mode3FullState(@Nullable byte[] theirActivePqPk,
			MlKemKeyPair ourActiveKeyPair,
			Map<KpId, MlKemKeyPair> recentKeyPairs, long messageCounter,
			@Nullable KpId peerUsedKpId) {
		if (theirActivePqPk != null &&
				theirActivePqPk.length != MLKEM_ENCAPSULATION_KEY_SIZE) {
			throw new IllegalArgumentException();
		}
		this.theirActivePqPk = theirActivePqPk;
		this.ourActiveKeyPair = ourActiveKeyPair;
		this.recentKeyPairs = Collections.unmodifiableMap(
				new LinkedHashMap<>(recentKeyPairs));
		this.messageCounter = messageCounter;
		this.peerUsedKpId = peerUsedKpId;
	}

	@Nullable
	public KpId getPeerUsedKpId() {
		return peerUsedKpId;
	}

	/**
	 * Whether the send side may retire the active key pair. Retiring moves
	 * it into the retention window, and the window is never allowed to
	 * overflow: the peer's frames are ordered, so every retired key pair
	 * older than the one the peer last used is dead and is pruned on
	 * receipt, but a peer whose frames have not been read for a while may
	 * still be using any of the retained ones. Rotation therefore pauses at
	 * the window bound instead of evicting a key pair the peer may need.
	 */
	public boolean canRotate() {
		return recentKeyPairs.size() < MODE3_FULL_RECV_SK_LRU_SIZE;
	}

	@Nullable
	public byte[] getTheirActivePqPk() {
		return theirActivePqPk;
	}

	public MlKemKeyPair getOurActiveKeyPair() {
		return ourActiveKeyPair;
	}

	public Map<KpId, MlKemKeyPair> getRecentKeyPairs() {
		return recentKeyPairs;
	}

	public long getMessageCounter() {
		return messageCounter;
	}

	@Nullable
	public MlKemKeyPair findKeypairById(KpId kpId) {
		KpId currentId = KpId.of(ourActiveKeyPair.getEncapsulationKey());
		if (currentId.equals(kpId)) return ourActiveKeyPair;
		return recentKeyPairs.get(kpId);
	}

	public Mode3FullState withSendAdvance(MlKemKeyPair newOurKp) {
		LinkedHashMap<KpId, MlKemKeyPair> newRecent =
				new LinkedHashMap<>(recentKeyPairs);
		KpId oldId = KpId.of(ourActiveKeyPair.getEncapsulationKey());
		newRecent.remove(oldId);
		newRecent.put(oldId, ourActiveKeyPair);
		while (newRecent.size() > MODE3_FULL_RECV_SK_LRU_SIZE) {
			Iterator<Map.Entry<KpId, MlKemKeyPair>> it =
					newRecent.entrySet().iterator();
			Map.Entry<KpId, MlKemKeyPair> evicted = it.next();
			it.remove();
			zeroize(evicted.getValue());
		}
		return new Mode3FullState(theirActivePqPk, newOurKp, newRecent,
				messageCounter + 1, peerUsedKpId);
	}

	public Mode3FullState withSendAdvanceNoRotate() {
		return new Mode3FullState(theirActivePqPk, ourActiveKeyPair,
				recentKeyPairs, messageCounter + 1, peerUsedKpId);
	}

	public Mode3FullState withRecvAdvance(byte[] theirNewPk) {
		return withRecvAdvance(theirNewPk, null);
	}

	/**
	 * Records the peer's newly advertised key and, when the opened frame
	 * carried a ciphertext, the id of our key pair it was encapsulated to.
	 * Every retired key pair older than that one can no longer be
	 * referenced by a later frame of the same ordered stream and is pruned.
	 */
	public Mode3FullState withRecvAdvance(byte[] theirNewPk,
			@Nullable KpId kpIdUsed) {
		if (theirNewPk.length != MLKEM_ENCAPSULATION_KEY_SIZE) {
			throw new IllegalArgumentException();
		}
		return new Mode3FullState(theirNewPk, ourActiveKeyPair,
				recentKeyPairs, messageCounter + 1, peerUsedKpId)
				.withPeerUsedKpId(kpIdUsed);
	}

	/**
	 * Returns this state with the peer's last used key pair id set to
	 * {@code kpIdUsed} (kept unchanged when null) and the retired key pairs
	 * older than it pruned. Used both on receipt and when the receive side
	 * merges its receive-owned fields into the newest shared state.
	 */
	public Mode3FullState withPeerUsedKpId(@Nullable KpId kpIdUsed) {
		if (kpIdUsed == null) return this;
		KpId activeId = KpId.of(ourActiveKeyPair.getEncapsulationKey());
		LinkedHashMap<KpId, MlKemKeyPair> kept = new LinkedHashMap<>();
		if (kpIdUsed.equals(activeId)) {
			for (MlKemKeyPair kp : recentKeyPairs.values()) zeroize(kp);
		} else if (recentKeyPairs.containsKey(kpIdUsed)) {
			boolean older = true;
			for (Map.Entry<KpId, MlKemKeyPair> e : recentKeyPairs.entrySet()) {
				if (e.getKey().equals(kpIdUsed)) older = false;
				if (older) zeroize(e.getValue());
				else kept.put(e.getKey(), e.getValue());
			}
		} else {
			kept.putAll(recentKeyPairs);
		}
		return new Mode3FullState(theirActivePqPk, ourActiveKeyPair, kept,
				messageCounter, kpIdUsed);
	}

	private static void zeroize(MlKemKeyPair kp) {
		Arrays.fill(kp.getDecapsulationKey(), (byte) 0);
	}
}

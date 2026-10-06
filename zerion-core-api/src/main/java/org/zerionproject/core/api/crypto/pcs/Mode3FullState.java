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

	public void destroy() {
		zeroize(ourActiveKeyPair);
		for (MlKemKeyPair kp : recentKeyPairs.values()) zeroize(kp);
	}

	@Nullable
	public MlKemKeyPair findKeypairById(KpId kpId) {
		KpId currentId = KpId.of(ourActiveKeyPair.getEncapsulationKey());
		MlKemKeyPair found = currentId.equals(kpId) ? ourActiveKeyPair
				: recentKeyPairs.get(kpId);
		if (found == null || found.isDestroyed()) return null;
		return found;
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

	public Mode3FullState withRecvAdvance(byte[] theirNewPk,
			@Nullable KpId kpIdUsed) {
		if (theirNewPk.length != MLKEM_ENCAPSULATION_KEY_SIZE) {
			throw new IllegalArgumentException();
		}
		return new Mode3FullState(theirNewPk, ourActiveKeyPair,
				recentKeyPairs, messageCounter + 1, peerUsedKpId)
				.withPeerUsedKpId(kpIdUsed);
	}

	public Mode3FullState withRecvAdvanceUnpruned(byte[] theirNewPk,
			@Nullable KpId kpIdUsed) {
		if (theirNewPk.length != MLKEM_ENCAPSULATION_KEY_SIZE) {
			throw new IllegalArgumentException();
		}
		return new Mode3FullState(theirNewPk, ourActiveKeyPair,
				recentKeyPairs, messageCounter + 1,
				kpIdUsed == null ? peerUsedKpId : kpIdUsed);
	}

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

package org.briarproject.bramble.api.crypto.pcs;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_ENCAPSULATION_KEY_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_RECV_SK_LRU_SIZE;

@Immutable
@NotNullByDefault
public class Mode3FullState {

	private final SecretKey ckPq;
	@Nullable
	private final byte[] theirActivePqPk;
	private final MlKemKeyPair ourActiveKeyPair;
	private final Map<KpId, MlKemKeyPair> recentKeyPairs;
	private final long messageCounter;

	public Mode3FullState(SecretKey ckPq,
			@Nullable byte[] theirActivePqPk,
			MlKemKeyPair ourActiveKeyPair,
			Map<KpId, MlKemKeyPair> recentKeyPairs, long messageCounter) {
		if (theirActivePqPk != null &&
				theirActivePqPk.length != MLKEM_ENCAPSULATION_KEY_SIZE) {
			throw new IllegalArgumentException();
		}
		this.ckPq = ckPq;
		this.theirActivePqPk = theirActivePqPk;
		this.ourActiveKeyPair = ourActiveKeyPair;
		this.recentKeyPairs = Collections.unmodifiableMap(
				new LinkedHashMap<>(recentKeyPairs));
		this.messageCounter = messageCounter;
	}

	public SecretKey getCkPq() {
		return ckPq;
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

	public Mode3FullState withSendAdvance(SecretKey newCkPq,
			MlKemKeyPair newOurKp) {
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
		return new Mode3FullState(newCkPq, theirActivePqPk, newOurKp,
				newRecent, messageCounter + 1);
	}

	public Mode3FullState withSendAdvanceNoRotate(SecretKey newCkPq) {
		return new Mode3FullState(newCkPq, theirActivePqPk, ourActiveKeyPair,
				recentKeyPairs, messageCounter + 1);
	}

	public Mode3FullState withRecvAdvance(SecretKey newCkPq,
			byte[] theirNewPk) {
		if (theirNewPk.length != MLKEM_ENCAPSULATION_KEY_SIZE) {
			throw new IllegalArgumentException();
		}
		return new Mode3FullState(newCkPq, theirNewPk, ourActiveKeyPair,
				recentKeyPairs, messageCounter + 1);
	}

	private static void zeroize(MlKemKeyPair kp) {
		Arrays.fill(kp.getDecapsulationKey(), (byte) 0);
	}
}

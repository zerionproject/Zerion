package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.KpId;
import org.zerionproject.core.api.crypto.pcs.MlKemEncapsulation;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.LinkedHashMap;

import javax.inject.Inject;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_CIPHERTEXT_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_SEND_ROTATION_INTERVAL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_ENCAPSULATION_KEY_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_CK_PQ_LABEL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_MK_LABEL;

@NotNullByDefault
class Mode3FullRatchetImpl implements Mode3FullRatchet {

	private final CryptoComponent crypto;
	private final MlKemProvider mlKemProvider;

	@Inject
	Mode3FullRatchetImpl(CryptoComponent crypto, MlKemProvider mlKemProvider) {
		this.crypto = crypto;
		this.mlKemProvider = mlKemProvider;
	}

	@Override
	public SecretKey deriveHybridMessageKey(SecretKey classicalMessageKey,
			byte[] sharedSecret) {
		return crypto.deriveKey(MODE3_FULL_MK_LABEL, classicalMessageKey,
				sharedSecret);
	}

	@Override
	public SecretKey mixPqSecretIntoChainKey(SecretKey chainKey,
			byte[] sharedSecret) {
		return crypto.deriveKey(MODE3_FULL_CK_PQ_LABEL, chainKey, sharedSecret);
	}

	@Override
	public Mode3FullState createInitialState() {
		MlKemKeyPair initialKp = mlKemProvider.generateKeyPair();
		return new Mode3FullState(null, initialKp, new LinkedHashMap<>(), 0);
	}

	@Override
	public PqSendResult pqEncapsulateSend(Mode3FullState state) {
		byte[] theirPk = state.getTheirActivePqPk();
		byte[] ct;
		byte[] sharedSecret;
		boolean rotate;
		KpId kpIdUsed;
		if (theirPk == null) {
			ct = new byte[MLKEM_CIPHERTEXT_SIZE];
			sharedSecret = null;
			rotate = false;
			kpIdUsed = null;
		} else {
			MlKemEncapsulation enc = mlKemProvider.encapsulate(theirPk);
			ct = enc.getCiphertext();
			sharedSecret = enc.getSharedSecret().clone();
			Arrays.fill(enc.getSharedSecret(), (byte) 0);
			rotate = state.getMessageCounter()
					% MODE3_FULL_SEND_ROTATION_INTERVAL == 0;
			kpIdUsed = KpId.of(theirPk);
		}

		MlKemKeyPair nextKp = rotate
				? mlKemProvider.generateKeyPair()
				: state.getOurActiveKeyPair();
		byte[] pkAdvertise = nextKp.getEncapsulationKey();

		Mode3FullState newState = rotate
				? state.withSendAdvance(nextKp)
				: state.withSendAdvanceNoRotate();

		return new PqSendResult(pkAdvertise, ct, kpIdUsed, sharedSecret,
				newState);
	}

	@Override
	public PqRecvResult pqDecapsulateRecv(Mode3FullState state, KpId kpId,
			byte[] ciphertext, byte[] theirNewPqPk) throws PcsException {
		if (ciphertext.length != MLKEM_CIPHERTEXT_SIZE) {
			throw new PcsException("Mode 3-Full CT length mismatch");
		}
		if (theirNewPqPk.length != MLKEM_ENCAPSULATION_KEY_SIZE) {
			throw new PcsException("Mode 3-Full advertised PK length mismatch");
		}

		byte[] sharedSecret = null;
		if (!isZeroSentinel(ciphertext)) {
			if (kpId == null) {
				throw new PcsException(
						"Mode 3-Full kpId missing for non-zero CT");
			}
			MlKemKeyPair kp = state.findKeypairById(kpId);
			if (kp == null) {
				throw new PcsException(
						"Mode 3-Full kpId not in retention window");
			}
			sharedSecret = mlKemProvider.decapsulate(
					kp.getDecapsulationKey(), ciphertext);
		}

		Mode3FullState newState = state.withRecvAdvance(theirNewPqPk);
		return new PqRecvResult(sharedSecret, newState);
	}

	private boolean isZeroSentinel(byte[] ct) {
		for (byte b : ct) {
			if (b != 0) return false;
		}
		return true;
	}
}

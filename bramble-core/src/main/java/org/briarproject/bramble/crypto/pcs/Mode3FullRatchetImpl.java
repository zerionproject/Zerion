package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.MlKemEncapsulation;
import org.briarproject.bramble.api.crypto.pcs.MlKemKeyPair;
import org.briarproject.bramble.api.crypto.pcs.MlKemProvider;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullRatchet;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullState;
import org.briarproject.bramble.api.crypto.pcs.PcsException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_CIPHERTEXT_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_ENCAPSULATION_KEY_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_CK_DH_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_INIT_SPLIT_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_MK_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_PQ_ABSORB_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_RECV_SK_LRU_SIZE;

@NotNullByDefault
class Mode3FullRatchetImpl implements Mode3FullRatchet {

	private static final byte CK_DH_INPUT = 0x01;
	private static final byte CK_PQ_INPUT = 0x02;

	private final CryptoComponent crypto;
	private final MlKemProvider mlKemProvider;

	@Inject
	Mode3FullRatchetImpl(CryptoComponent crypto, MlKemProvider mlKemProvider) {
		this.crypto = crypto;
		this.mlKemProvider = mlKemProvider;
	}

	@Override
	public Mode3FullState createInitialState(SecretKey rootKey) {
		SecretKey ckDh = crypto.deriveKey(MODE3_FULL_INIT_SPLIT_LABEL,
				rootKey, new byte[]{CK_DH_INPUT});
		SecretKey ckPq = crypto.deriveKey(MODE3_FULL_INIT_SPLIT_LABEL,
				rootKey, new byte[]{CK_PQ_INPUT});
		MlKemKeyPair initialKp = mlKemProvider.generateKeyPair();
		return new Mode3FullState(ckDh, ckPq, null, initialKp,
				new ArrayDeque<>(MODE3_FULL_RECV_SK_LRU_SIZE), 0);
	}

	@Override
	public SendChainResult advanceSendChain(Mode3FullState state) {
		byte[] theirPk = state.getTheirActivePqPk();
		byte[] ct;
		byte[] sharedSecret;
		if (theirPk == null) {
			ct = new byte[MLKEM_CIPHERTEXT_SIZE];
			sharedSecret = null;
		} else {
			MlKemEncapsulation enc = mlKemProvider.encapsulate(theirPk);
			ct = enc.getCiphertext();
			sharedSecret = enc.getSharedSecret().clone();
			Arrays.fill(enc.getSharedSecret(), (byte) 0);
		}

		SecretKey newCkPq = absorbPq(state.getCkPq(), sharedSecret);
		SecretKey newCkDh = advanceCkDh(state.getCkDh());
		SecretKey messageKey = deriveMessageKey(newCkDh, newCkPq);

		if (sharedSecret != null) Arrays.fill(sharedSecret, (byte) 0);

		MlKemKeyPair nextKp = mlKemProvider.generateKeyPair();
		byte[] pkAdvertise = nextKp.getEncapsulationKey();

		Mode3FullState newState = state.withSendAdvance(newCkDh, newCkPq,
				nextKp);

		return new SendChainResult(messageKey, pkAdvertise, ct, newState);
	}

	@Override
	public RecvChainResult advanceRecvChain(Mode3FullState state,
			byte[] ciphertext, byte[] theirNewPqPk) throws PcsException {
		if (ciphertext.length != MLKEM_CIPHERTEXT_SIZE) {
			throw new PcsException("Mode 3-Full CT length mismatch");
		}
		if (theirNewPqPk.length != MLKEM_ENCAPSULATION_KEY_SIZE) {
			throw new PcsException("Mode 3-Full advertised PK length mismatch");
		}

		byte[] sharedSecret = null;
		if (!isZeroSentinel(ciphertext)) {
			sharedSecret = tryDecapsulate(state, ciphertext);
			if (sharedSecret == null) {
				throw new PcsException(
						"Mode 3-Full decapsulation failed: no SK matched");
			}
		}

		SecretKey newCkPq = absorbPq(state.getCkPq(), sharedSecret);
		SecretKey newCkDh = advanceCkDh(state.getCkDh());
		SecretKey messageKey = deriveMessageKey(newCkDh, newCkPq);

		if (sharedSecret != null) Arrays.fill(sharedSecret, (byte) 0);

		Mode3FullState newState = state.withRecvAdvance(newCkDh, newCkPq,
				theirNewPqPk);

		return new RecvChainResult(messageKey, newState);
	}

	@Nullable
	private byte[] tryDecapsulate(Mode3FullState state, byte[] ciphertext) {
		try {
			return mlKemProvider.decapsulate(
					state.getOurActiveKeyPair().getDecapsulationKey(),
					ciphertext);
		} catch (RuntimeException e) {
			// fall through to LRU
		}
		Deque<MlKemKeyPair> recent = state.getRecentKeyPairs();
		for (MlKemKeyPair kp : recent) {
			try {
				return mlKemProvider.decapsulate(
						kp.getDecapsulationKey(), ciphertext);
			} catch (RuntimeException e) {
				// keep trying
			}
		}
		return null;
	}

	private SecretKey advanceCkDh(SecretKey ckDh) {
		return crypto.deriveKey(MODE3_FULL_CK_DH_LABEL, ckDh,
				new byte[]{CK_DH_INPUT});
	}

	private SecretKey absorbPq(SecretKey ckPq, @Nullable byte[] sharedSecret) {
		if (sharedSecret == null) {
			return crypto.deriveKey(MODE3_FULL_PQ_ABSORB_LABEL, ckPq,
					new byte[]{CK_PQ_INPUT});
		}
		return crypto.deriveKey(MODE3_FULL_PQ_ABSORB_LABEL, ckPq,
				sharedSecret);
	}

	private SecretKey deriveMessageKey(SecretKey ckDh, SecretKey ckPq) {
		return crypto.deriveKey(MODE3_FULL_MK_LABEL, ckDh, ckPq.getBytes());
	}

	private boolean isZeroSentinel(byte[] ct) {
		for (byte b : ct) {
			if (b != 0) return false;
		}
		return true;
	}
}

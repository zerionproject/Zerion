package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.KpId;
import org.briarproject.bramble.api.crypto.pcs.MlKemEncapsulation;
import org.briarproject.bramble.api.crypto.pcs.MlKemKeyPair;
import org.briarproject.bramble.api.crypto.pcs.MlKemProvider;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullRatchet;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullState;
import org.briarproject.bramble.api.crypto.pcs.PcsException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_CIPHERTEXT_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_ENCAPSULATION_KEY_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_INIT_SPLIT_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_MK_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_PQ_ABSORB_LABEL;

@NotNullByDefault
class Mode3FullRatchetImpl implements Mode3FullRatchet {

	private static final Logger LOG =
			Logger.getLogger(Mode3FullRatchetImpl.class.getName());

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
		SecretKey ckPq = crypto.deriveKey(MODE3_FULL_INIT_SPLIT_LABEL,
				rootKey, new byte[]{CK_PQ_INPUT});
		MlKemKeyPair initialKp = mlKemProvider.generateKeyPair();
		LOG.warning("[ZER-PQ-DEBUG] Mode3Full INIT — fresh ML-KEM-768 KP" +
				" generated (pubkey=" + initialKp.getEncapsulationKey().length +
				" B, privkey=" + initialKp.getDecapsulationKey().length +
				" B); CK_pq derived from root");
		return new Mode3FullState(ckPq, null, initialKp,
				new LinkedHashMap<>(), 0);
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
			LOG.warning("[ZER-PQ-DEBUG] Mode3Full SEND — first frame," +
					" peer PK unknown, emitting zero-CT sentinel; no PQ" +
					" absorb, no KP rotation (CK_pq advances symmetrically)");
		} else {
			MlKemEncapsulation enc = mlKemProvider.encapsulate(theirPk);
			ct = enc.getCiphertext();
			sharedSecret = enc.getSharedSecret().clone();
			Arrays.fill(enc.getSharedSecret(), (byte) 0);
			rotate = true;
			kpIdUsed = KpId.of(theirPk);
			LOG.warning("[ZER-PQ-DEBUG] Mode3Full SEND — per-message" +
					" ML-KEM-768 encap (peerPK=" + theirPk.length + " B, CT=" +
					ct.length + " B), ss absorbed into CK_pq, fresh ephemeral" +
					" KP rotated; msg counter=" + state.getMessageCounter());
		}

		SecretKey newCkPq = absorbPq(state.getCkPq(), sharedSecret);
		if (sharedSecret != null) Arrays.fill(sharedSecret, (byte) 0);

		MlKemKeyPair nextKp = rotate
				? mlKemProvider.generateKeyPair()
				: state.getOurActiveKeyPair();
		byte[] pkAdvertise = nextKp.getEncapsulationKey();

		Mode3FullState newState = rotate
				? state.withSendAdvance(newCkPq, nextKp)
				: state.withSendAdvanceNoRotate(newCkPq);

		return new PqSendResult(pkAdvertise, ct, kpIdUsed, newCkPq, newState);
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
				throw new PcsException("Mode 3-Full kpId missing for non-zero CT");
			}
			MlKemKeyPair kp = state.findKeypairById(kpId);
			if (kp == null) {
				throw new PcsException(
						"Mode 3-Full kpId not in retention window");
			}
			sharedSecret = mlKemProvider.decapsulate(
					kp.getDecapsulationKey(), ciphertext);
			LOG.warning("[ZER-PQ-DEBUG] Mode3Full RECV — per-message" +
					" ML-KEM-768 decap OK (ss=" + sharedSecret.length +
					" B), absorbing into CK_pq; msg counter=" +
					state.getMessageCounter());
		} else {
			LOG.warning("[ZER-PQ-DEBUG] Mode3Full RECV — peer sent" +
					" zero-CT sentinel (first frame), no PQ absorb");
		}

		SecretKey newCkPq = absorbPq(state.getCkPq(), sharedSecret);
		if (sharedSecret != null) Arrays.fill(sharedSecret, (byte) 0);

		Mode3FullState newState = state.withRecvAdvance(newCkPq, theirNewPqPk);

		return new PqRecvResult(newCkPq, newState);
	}

	@Override
	public SecretKey deriveHybridMessageKey(SecretKey classicalMessageKey,
			SecretKey ckPq) {
		return crypto.deriveKey(MODE3_FULL_MK_LABEL,
				classicalMessageKey, ckPq.getBytes());
	}

	private SecretKey absorbPq(SecretKey ckPq, @Nullable byte[] sharedSecret) {
		if (sharedSecret == null) {
			return crypto.deriveKey(MODE3_FULL_PQ_ABSORB_LABEL, ckPq,
					new byte[]{CK_PQ_INPUT});
		}
		return crypto.deriveKey(MODE3_FULL_PQ_ABSORB_LABEL, ckPq,
				sharedSecret);
	}

	private boolean isZeroSentinel(byte[] ct) {
		for (byte b : ct) {
			if (b != 0) return false;
		}
		return true;
	}
}

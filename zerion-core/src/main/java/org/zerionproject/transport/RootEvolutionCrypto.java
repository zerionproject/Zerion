package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class RootEvolutionCrypto {

	static final String LABEL_EPOCH_TRANSPORT =
			"org.zerionproject.transport/ROOT_EPOCH_TRANSPORT_V1";
	static final String LABEL_EPOCH_CHAIN =
			"org.zerionproject.transport/ROOT_EPOCH_CHAIN_V1";
	static final String LABEL_TRANSCRIPT =
			"org.zerionproject.transport/ROOT_EVOLVE_TRANSCRIPT_V1";
	static final String LABEL_DH =
			"org.zerionproject.transport/ROOT_EVOLVE_DH_V1";
	static final String LABEL_ROOT =
			"org.zerionproject.transport/ROOT_EVOLVE_V1";
	static final String LABEL_CONFIRM_KEY =
			"org.zerionproject.transport/ROOT_EVOLVE_CONFIRM_KEY_V1";
	static final String LABEL_RESPONDER_CONFIRM =
			"org.zerionproject.transport/ROOT_EVOLVE_RESPONDER_CONFIRM_V1";
	static final String LABEL_INITIATOR_CONFIRM =
			"org.zerionproject.transport/ROOT_EVOLVE_INITIATOR_CONFIRM_V1";
	static final String LABEL_RESPONDER_DONE =
			"org.zerionproject.transport/ROOT_EVOLVE_RESPONDER_DONE_V1";
	static final String LABEL_PENDING_ID =
			"org.zerionproject.transport/ROOT_PENDING_ID_V1";

	private final CryptoComponent crypto;

	public RootEvolutionCrypto(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	public SecretKey transportRoot(SecretKey root, long epoch) {
		if (epoch == 0) return root;
		return crypto.deriveKey(LABEL_EPOCH_TRANSPORT, root, u64(epoch));
	}

	byte[] transcriptHash(long epoch, byte[] initiatorX25519,
			byte[] initiatorEk, byte[] responderX25519, byte[] ciphertext) {
		return crypto.hash(LABEL_TRANSCRIPT,
				new byte[] {RootEvolutionRecord.VERSION}, u64(epoch),
				initiatorX25519, initiatorEk, responderX25519, ciphertext);
	}

	SecretKey agree(PublicKey theirX25519, KeyPair ourX25519,
			byte[] initiatorX25519, byte[] responderX25519)
			throws GeneralSecurityException {
		return crypto.deriveSharedSecret(LABEL_DH, theirX25519, ourX25519,
				initiatorX25519, responderX25519);
	}

	SecretKey nextRoot(SecretKey root, long epoch, byte[] kemSecret,
			SecretKey dh, byte[] transcriptHash) {
		SecretKey chain = crypto.deriveKey(LABEL_EPOCH_CHAIN, root,
				u64(epoch));
		try {
			return crypto.deriveKey(LABEL_ROOT, chain, u64(epoch + 1),
					kemSecret, dh.getBytes(), transcriptHash);
		} finally {
			chain.clear();
		}
	}

	byte[] responderConfirm(SecretKey next, long nextEpoch) {
		return confirmMac(LABEL_RESPONDER_CONFIRM, next, u64(nextEpoch));
	}

	byte[] initiatorConfirm(SecretKey next, long nextEpoch) {
		return confirmMac(LABEL_INITIATOR_CONFIRM, next, u64(nextEpoch));
	}

	byte[] responderDone(SecretKey next, long nextEpoch) {
		return confirmMac(LABEL_RESPONDER_DONE, next, u64(nextEpoch));
	}

	boolean verifyResponderConfirm(byte[] mac, SecretKey next,
			long nextEpoch) {
		return verifyConfirmMac(mac, LABEL_RESPONDER_CONFIRM, next,
				u64(nextEpoch));
	}

	boolean verifyInitiatorConfirm(byte[] mac, SecretKey next,
			long nextEpoch) {
		return verifyConfirmMac(mac, LABEL_INITIATOR_CONFIRM, next,
				u64(nextEpoch));
	}

	boolean verifyResponderDone(byte[] mac, SecretKey next, long nextEpoch) {
		return verifyConfirmMac(mac, LABEL_RESPONDER_DONE, next,
				u64(nextEpoch));
	}

	byte[] pendingId(SecretKey pending, long pendingEpoch) {
		return confirmMac(LABEL_PENDING_ID, pending, u64(pendingEpoch));
	}

	boolean verifyPendingId(byte[] id, SecretKey pending, long pendingEpoch) {
		return verifyConfirmMac(id, LABEL_PENDING_ID, pending,
				u64(pendingEpoch));
	}

	private byte[] confirmMac(String label, SecretKey root, byte[] input) {
		SecretKey confirmKey = crypto.deriveKey(LABEL_CONFIRM_KEY, root);
		try {
			return crypto.mac(label, confirmKey, input);
		} finally {
			confirmKey.clear();
		}
	}

	private boolean verifyConfirmMac(byte[] mac, String label, SecretKey root,
			byte[] input) {
		SecretKey confirmKey = crypto.deriveKey(LABEL_CONFIRM_KEY, root);
		try {
			return crypto.verifyMac(mac, label, confirmKey, input);
		} finally {
			confirmKey.clear();
		}
	}

	static byte[] u64(long v) {
		byte[] b = new byte[ByteUtils.INT_64_BYTES];
		ByteUtils.writeUint64(v, b, 0);
		return b;
	}
}

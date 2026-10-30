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
	static final String LABEL_SEPARATED_CONFIRM_KEY =
			"org.zerionproject.transport/ROOT_EVOLVE_CONFIRM_KEY_V3";
	static final String LABEL_RESPONDER_CONFIRM =
			"org.zerionproject.transport/ROOT_EVOLVE_RESPONDER_CONFIRM_V1";
	static final String LABEL_INITIATOR_CONFIRM =
			"org.zerionproject.transport/ROOT_EVOLVE_INITIATOR_CONFIRM_V1";
	static final String LABEL_RESPONDER_DONE =
			"org.zerionproject.transport/ROOT_EVOLVE_RESPONDER_DONE_V1";
	static final String LABEL_PENDING_ID =
			"org.zerionproject.transport/ROOT_PENDING_ID_V1";

	@Immutable
	@NotNullByDefault
	static final class EvolvedKeys {

		final byte version;
		final SecretKey root;
		final SecretKey confirmKey;

		EvolvedKeys(byte version, SecretKey root, SecretKey confirmKey) {
			this.version = version;
			this.root = root;
			this.confirmKey = confirmKey;
		}

		boolean isSeparated() {
			return version == RootEvolutionRecord.VERSION_SEPARATED;
		}

		void clear() {
			root.clear();
			confirmKey.clear();
		}
	}

	private final CryptoComponent crypto;

	public RootEvolutionCrypto(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	public SecretKey transportRoot(SecretKey root, long epoch) {
		if (epoch == 0) return root;
		return crypto.deriveKey(LABEL_EPOCH_TRANSPORT, root, u64(epoch));
	}

	byte[] transcriptHash(byte version, long epoch, byte[] initiatorX25519,
			byte[] initiatorEk, byte[] responderX25519, byte[] ciphertext) {
		return crypto.hash(LABEL_TRANSCRIPT, new byte[] {version}, u64(epoch),
				initiatorX25519, initiatorEk, responderX25519, ciphertext);
	}

	SecretKey agree(PublicKey theirX25519, KeyPair ourX25519,
			byte[] initiatorX25519, byte[] responderX25519)
			throws GeneralSecurityException {
		return crypto.deriveSharedSecret(LABEL_DH, theirX25519, ourX25519,
				initiatorX25519, responderX25519);
	}

	EvolvedKeys evolve(byte version, SecretKey root, long epoch,
			byte[] kemSecret, SecretKey dh, byte[] transcriptHash) {
		if (version != RootEvolutionRecord.VERSION
				&& version != RootEvolutionRecord.VERSION_SEPARATED) {
			throw new IllegalArgumentException();
		}
		SecretKey chain = crypto.deriveKey(LABEL_EPOCH_CHAIN, root,
				u64(epoch));
		try {
			SecretKey next = crypto.deriveKey(LABEL_ROOT, chain,
					u64(epoch + 1), kemSecret, dh.getBytes(), transcriptHash);
			SecretKey confirmKey;
			if (version == RootEvolutionRecord.VERSION_SEPARATED) {
				confirmKey = crypto.deriveKey(LABEL_SEPARATED_CONFIRM_KEY,
						chain, u64(epoch + 1), kemSecret, dh.getBytes(),
						transcriptHash);
			} else {
				confirmKey = legacyConfirmKey(next);
			}
			return new EvolvedKeys(version, next, confirmKey);
		} finally {
			chain.clear();
		}
	}

	SecretKey legacyConfirmKey(SecretKey next) {
		return crypto.deriveKey(LABEL_CONFIRM_KEY, next);
	}

	SecretKey pendingConfirmKey(ContactRootKeys keys) {
		SecretKey pending = keys.getPending();
		if (pending == null) throw new IllegalStateException();
		SecretKey stored = keys.getPendingConfirmKey();
		if (stored != null) return new SecretKey(stored.getBytes().clone());
		return legacyConfirmKey(pending);
	}

	static byte pendingVersion(ContactRootKeys keys) {
		return keys.isPendingSeparated()
				? RootEvolutionRecord.VERSION_SEPARATED
				: RootEvolutionRecord.VERSION;
	}

	byte[] responderConfirmWith(SecretKey confirmKey, long nextEpoch) {
		return crypto.mac(LABEL_RESPONDER_CONFIRM, confirmKey, u64(nextEpoch));
	}

	byte[] initiatorConfirmWith(SecretKey confirmKey, long nextEpoch) {
		return crypto.mac(LABEL_INITIATOR_CONFIRM, confirmKey, u64(nextEpoch));
	}

	byte[] responderDoneWith(SecretKey confirmKey, long nextEpoch) {
		return crypto.mac(LABEL_RESPONDER_DONE, confirmKey, u64(nextEpoch));
	}

	byte[] pendingIdWith(SecretKey confirmKey, long pendingEpoch) {
		return crypto.mac(LABEL_PENDING_ID, confirmKey, u64(pendingEpoch));
	}

	boolean verifyResponderConfirmWith(byte[] mac, SecretKey confirmKey,
			long nextEpoch) {
		return crypto.verifyMac(mac, LABEL_RESPONDER_CONFIRM, confirmKey,
				u64(nextEpoch));
	}

	boolean verifyInitiatorConfirmWith(byte[] mac, SecretKey confirmKey,
			long nextEpoch) {
		return crypto.verifyMac(mac, LABEL_INITIATOR_CONFIRM, confirmKey,
				u64(nextEpoch));
	}

	boolean verifyResponderDoneWith(byte[] mac, SecretKey confirmKey,
			long nextEpoch) {
		return crypto.verifyMac(mac, LABEL_RESPONDER_DONE, confirmKey,
				u64(nextEpoch));
	}

	boolean verifyPendingIdWith(byte[] id, SecretKey confirmKey,
			long pendingEpoch) {
		return crypto.verifyMac(id, LABEL_PENDING_ID, confirmKey,
				u64(pendingEpoch));
	}

	static byte[] u64(long v) {
		byte[] b = new byte[ByteUtils.INT_64_BYTES];
		ByteUtils.writeUint64(v, b, 0);
		return b;
	}
}

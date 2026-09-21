package org.zerionproject.core.keyagreement;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridEncapsulationResult;
import org.zerionproject.core.api.crypto.KeyAgreementCrypto;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.keyagreement.Payload;
import org.zerionproject.core.api.keyagreement.PayloadEncoder;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.HYBRID_SHARED_SECRET_LABEL;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.MASTER_KEY_LABEL;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.PROTOCOL_VERSION;

@NotNullByDefault
class KeyAgreementProtocol {

	interface Callbacks {

		void connectionWaiting();

		void initialRecordReceived();
	}

	private final Callbacks callbacks;
	private final CryptoComponent crypto;
	private final KeyAgreementCrypto keyAgreementCrypto;
	private final PayloadEncoder payloadEncoder;
	private final KeyAgreementTransport transport;
	private final Payload theirPayload, ourPayload;
	private final KeyPair ourKeyPair;
	private final boolean alice;

	KeyAgreementProtocol(Callbacks callbacks, CryptoComponent crypto,
			KeyAgreementCrypto keyAgreementCrypto,
			PayloadEncoder payloadEncoder, KeyAgreementTransport transport,
			Payload theirPayload, Payload ourPayload, KeyPair ourKeyPair,
			boolean alice) {
		this.callbacks = callbacks;
		this.crypto = crypto;
		this.keyAgreementCrypto = keyAgreementCrypto;
		this.payloadEncoder = payloadEncoder;
		this.transport = transport;
		this.theirPayload = theirPayload;
		this.ourPayload = ourPayload;
		this.ourKeyPair = ourKeyPair;
		this.alice = alice;
	}

	/**
	 * Runs the nearby pairing protocol with hybrid X25519 and ML-KEM-768
	 * keys. After the key exchange Alice encapsulates an ML-KEM secret to
	 * Bob's key and sends the ciphertext; both sides then derive the shared
	 * secret from the X25519 agreement and the ML-KEM secret, so the master
	 * key that every contact key descends from is post-quantum.
	 */
	SecretKey perform() throws AbortException, IOException {
		try {
			PublicKey theirPublicKey;
			byte[] kemCiphertext;
			byte[] kemSecret = new byte[0];
			SecretKey s;
			try {
				if (alice) {
					sendKey();
					callbacks.connectionWaiting();
					theirPublicKey = receiveKey();
					HybridEncapsulationResult enc = encapsulate(theirPublicKey);
					kemCiphertext = enc.getCiphertext();
					kemSecret = enc.getSharedSecret();
					transport.sendKemCiphertext(kemCiphertext);
					s = deriveSharedSecretAsEncapsulator(theirPublicKey,
							kemCiphertext, kemSecret);
				} else {
					theirPublicKey = receiveKey();
					sendKey();
					kemCiphertext = receiveKemCiphertext();
					s = deriveSharedSecretAsDecapsulator(theirPublicKey,
							kemCiphertext);
				}
			} finally {
				java.util.Arrays.fill(kemSecret, (byte) 0);
			}
			if (alice) {
				sendConfirm(s, theirPublicKey);
				receiveConfirm(s, theirPublicKey);
			} else {
				receiveConfirm(s, theirPublicKey);
				sendConfirm(s, theirPublicKey);
			}
			SecretKey masterKey = crypto.deriveKey(MASTER_KEY_LABEL, s);

			java.util.Arrays.fill(s.getBytes(), (byte) 0);
			return masterKey;
		} catch (AbortException e) {
			sendAbort(e.getCause() != null);
			throw e;
		}
	}

	private void sendKey() throws IOException {
		transport.sendKey(ourKeyPair.getPublic().getEncoded());
	}

	private PublicKey receiveKey() throws AbortException {
		byte[] publicKeyBytes = transport.receiveKey();
		callbacks.initialRecordReceived();
		KeyParser keyParser = crypto.getHybridAgreementKeyParser();
		try {
			PublicKey publicKey = keyParser.parsePublicKey(publicKeyBytes);
			byte[] expected = keyAgreementCrypto.deriveKeyCommitment(publicKey);

			if (!MessageDigest.isEqual(expected, theirPayload.getCommitment()))
				throw new AbortException();
			return publicKey;
		} catch (GeneralSecurityException e) {
			throw new AbortException();
		}
	}

	private HybridEncapsulationResult encapsulate(PublicKey theirPublicKey)
			throws AbortException {
		try {
			return crypto.hybridEncapsulate(theirPublicKey);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new AbortException(e);
		}
	}

	private byte[] receiveKemCiphertext() throws AbortException {
		byte[] ciphertext = transport.receiveKemCiphertext();
		if (ciphertext.length != ML_KEM_768_CIPHERTEXT_BYTES) {
			throw new AbortException();
		}
		return ciphertext;
	}

	private byte[][] sharedSecretInputs(PublicKey theirPublicKey,
			byte[] kemCiphertext) {
		byte[] ourPublicKeyBytes = ourKeyPair.getPublic().getEncoded();
		byte[] theirPublicKeyBytes = theirPublicKey.getEncoded();
		return new byte[][] {
				new byte[] {PROTOCOL_VERSION},
				alice ? ourPublicKeyBytes : theirPublicKeyBytes,
				alice ? theirPublicKeyBytes : ourPublicKeyBytes,
				kemCiphertext
		};
	}

	private SecretKey deriveSharedSecretAsEncapsulator(
			PublicKey theirPublicKey, byte[] kemCiphertext, byte[] kemSecret)
			throws AbortException {
		try {
			return crypto.deriveHybridSharedSecretAsResponder(
					HYBRID_SHARED_SECRET_LABEL, theirPublicKey, ourKeyPair,
					kemSecret, sharedSecretInputs(theirPublicKey,
							kemCiphertext));
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new AbortException(e);
		}
	}

	private SecretKey deriveSharedSecretAsDecapsulator(
			PublicKey theirPublicKey, byte[] kemCiphertext)
			throws AbortException {
		try {
			return crypto.deriveHybridSharedSecret(HYBRID_SHARED_SECRET_LABEL,
					theirPublicKey, ourKeyPair, kemCiphertext,
					sharedSecretInputs(theirPublicKey, kemCiphertext));
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new AbortException(e);
		}
	}

	private void sendConfirm(SecretKey s, PublicKey theirPublicKey)
			throws IOException {
		byte[] confirm = keyAgreementCrypto.deriveConfirmationRecord(s,
				payloadEncoder.encode(theirPayload),
				payloadEncoder.encode(ourPayload),
				theirPublicKey, ourKeyPair,
				alice, alice);
		transport.sendConfirm(confirm);
	}

	private void receiveConfirm(SecretKey s, PublicKey theirPublicKey)
			throws AbortException {
		byte[] confirm = transport.receiveConfirm();
		byte[] expected = keyAgreementCrypto.deriveConfirmationRecord(s,
				payloadEncoder.encode(theirPayload),
				payloadEncoder.encode(ourPayload),
				theirPublicKey, ourKeyPair,
				alice, !alice);

		if (!MessageDigest.isEqual(expected, confirm))
			throw new AbortException();
	}

	private void sendAbort(boolean exception) {
		transport.sendAbort(exception);
	}
}

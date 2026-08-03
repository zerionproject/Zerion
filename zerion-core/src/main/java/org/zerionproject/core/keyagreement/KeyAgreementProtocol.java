package org.zerionproject.core.keyagreement;

import org.zerionproject.core.api.crypto.CryptoComponent;
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

import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.MASTER_KEY_LABEL;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.SHARED_SECRET_LABEL;

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

	SecretKey perform() throws AbortException, IOException {
		try {
			PublicKey theirPublicKey;
			if (alice) {
				sendKey();
				callbacks.connectionWaiting();
				theirPublicKey = receiveKey();
			} else {
				theirPublicKey = receiveKey();
				sendKey();
			}
			SecretKey s = deriveSharedSecret(theirPublicKey);
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
		KeyParser keyParser = crypto.getAgreementKeyParser();
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

	private SecretKey deriveSharedSecret(PublicKey theirPublicKey)
			throws AbortException {
		try {
			byte[] ourPublicKeyBytes = ourKeyPair.getPublic().getEncoded();
			byte[] theirPublicKeyBytes = theirPublicKey.getEncoded();
			byte[][] inputs = {
					new byte[] {PROTOCOL_VERSION},
					alice ? ourPublicKeyBytes : theirPublicKeyBytes,
					alice ? theirPublicKeyBytes : ourPublicKeyBytes
			};
			return crypto.deriveSharedSecret(SHARED_SECRET_LABEL,
					theirPublicKey, ourKeyPair, inputs);
		} catch (GeneralSecurityException e) {
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

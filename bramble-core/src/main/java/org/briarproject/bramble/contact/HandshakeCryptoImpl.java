package org.briarproject.bramble.contact;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.HybridEncapsulationResult;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.bramble.contact.HandshakeConstants.ALICE_PROOF_LABEL;
import static org.briarproject.bramble.contact.HandshakeConstants.BOB_PROOF_LABEL;
import static org.briarproject.bramble.contact.HandshakeConstants.MASTER_KEY_LABEL_0_0;
import static org.briarproject.bramble.contact.HandshakeConstants.MASTER_KEY_LABEL_0_1;
import static org.briarproject.bramble.contact.HandshakeConstants.MASTER_KEY_LABEL_HYBRID;

@Immutable
@NotNullByDefault
class HandshakeCryptoImpl implements HandshakeCrypto {

	private final CryptoComponent crypto;

	@Inject
	HandshakeCryptoImpl(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	@Override
	public KeyPair generateEphemeralKeyPair() {
		return crypto.generateAgreementKeyPair();
	}

	@Override
	public KeyPair generateHybridEphemeralKeyPair() {
		return crypto.generateHybridAgreementKeyPair();
	}

	@Override
	@Deprecated
	public SecretKey deriveMasterKey_0_0(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, boolean alice) throws
			GeneralSecurityException {
		byte[] theirStatic = theirStaticPublicKey.getEncoded();
		byte[] theirEphemeral = theirEphemeralPublicKey.getEncoded();
		byte[] ourStatic = ourStaticKeyPair.getPublic().getEncoded();
		byte[] ourEphemeral = ourEphemeralKeyPair.getPublic().getEncoded();
		byte[][] inputs = {
				alice ? ourStatic : theirStatic,
				alice ? theirStatic : ourStatic,
				alice ? ourEphemeral : theirEphemeral,
				alice ? theirEphemeral : ourEphemeral
		};
		return crypto.deriveSharedSecretBadly(MASTER_KEY_LABEL_0_0,
				theirStaticPublicKey, theirEphemeralPublicKey,
				ourStaticKeyPair, ourEphemeralKeyPair, alice, inputs);
	}

	@Override
	public SecretKey deriveMasterKey_0_1(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, boolean alice) throws
			GeneralSecurityException {
		byte[] theirStatic = theirStaticPublicKey.getEncoded();
		byte[] theirEphemeral = theirEphemeralPublicKey.getEncoded();
		byte[] ourStatic = ourStaticKeyPair.getPublic().getEncoded();
		byte[] ourEphemeral = ourEphemeralKeyPair.getPublic().getEncoded();
		byte[][] inputs = {
				alice ? ourStatic : theirStatic,
				alice ? theirStatic : ourStatic,
				alice ? ourEphemeral : theirEphemeral,
				alice ? theirEphemeral : ourEphemeral
		};
		return crypto.deriveSharedSecret(MASTER_KEY_LABEL_0_1,
				theirStaticPublicKey, theirEphemeralPublicKey,
				ourStaticKeyPair, ourEphemeralKeyPair, alice, inputs);
	}

	@Override
	public HybridEncapsulationResult hybridEncapsulate(PublicKey theirPublicKey)
			throws GeneralSecurityException {
		return crypto.hybridEncapsulate(theirPublicKey);
	}

	@Override
	public SecretKey deriveHybridMasterKey(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, byte[] kemCiphertext,
			byte[] kemSecret, boolean alice) throws GeneralSecurityException {
		// Build inputs for key derivation (public keys in canonical order)
		byte[] theirStatic = theirStaticPublicKey.getEncoded();
		byte[] theirEphemeral = theirEphemeralPublicKey.getEncoded();
		byte[] ourStatic = ourStaticKeyPair.getPublic().getEncoded();
		byte[] ourEphemeral = ourEphemeralKeyPair.getPublic().getEncoded();
		byte[][] inputs = {
				alice ? ourStatic : theirStatic,
				alice ? theirStatic : ourStatic,
				alice ? ourEphemeral : theirEphemeral,
				alice ? theirEphemeral : ourEphemeral,
				kemCiphertext // Include KEM ciphertext in derivation
		};

		// Derive shared secret using hybrid key agreement
		// Alice (initiator) encapsulated and has kemSecret
		// Bob (responder) decapsulates using kemCiphertext
		if (alice) {
			// We encapsulated, use our kemSecret
			return crypto.deriveHybridSharedSecretAsResponder(
					MASTER_KEY_LABEL_HYBRID,
					theirStaticPublicKey,
					ourStaticKeyPair,
					kemSecret,
					inputs);
		} else {
			// We need to decapsulate their KEM ciphertext
			return crypto.deriveHybridSharedSecret(
					MASTER_KEY_LABEL_HYBRID,
					theirStaticPublicKey,
					ourStaticKeyPair,
					kemCiphertext,
					inputs);
		}
	}

	@Override
	public byte[] proveOwnership(SecretKey masterKey, boolean alice) {
		String label = alice ? ALICE_PROOF_LABEL : BOB_PROOF_LABEL;
		return crypto.mac(label, masterKey);
	}

	@Override
	public boolean verifyOwnership(SecretKey masterKey, boolean alice,
			byte[] proof) {
		String label = alice ? ALICE_PROOF_LABEL : BOB_PROOF_LABEL;
		return crypto.verifyMac(proof, label, masterKey);
	}
}

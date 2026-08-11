package org.zerionproject.core.contact;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridEncapsulationResult;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.core.contact.HandshakeConstants.ALICE_PROOF_LABEL;
import static org.zerionproject.core.contact.HandshakeConstants.BOB_PROOF_LABEL;
import static org.zerionproject.core.contact.HandshakeConstants.MASTER_KEY_LABEL_HYBRID;
import static org.zerionproject.core.contact.HandshakeConstants.MASTER_KEY_LABEL_HYBRID_FS;

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
	public HybridEncapsulationResult hybridEncapsulate(PublicKey theirPublicKey)
			throws GeneralSecurityException {
		return crypto.hybridEncapsulate(theirPublicKey);
	}

	@Override
	public SecretKey deriveHybridMasterKey(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, byte[] kemCiphertext,
			byte[] kemSecret, boolean alice) throws GeneralSecurityException {
		byte[] theirStatic = theirStaticPublicKey.getEncoded();
		byte[] theirEphemeral = theirEphemeralPublicKey.getEncoded();
		byte[] ourStatic = ourStaticKeyPair.getPublic().getEncoded();
		byte[] ourEphemeral = ourEphemeralKeyPair.getPublic().getEncoded();
		byte[][] inputs = {
				alice ? ourStatic : theirStatic,
				alice ? theirStatic : ourStatic,
				alice ? ourEphemeral : theirEphemeral,
				alice ? theirEphemeral : ourEphemeral,
				kemCiphertext
		};
		if (alice) {
			return crypto.deriveHybridSharedSecretAsResponder(
					MASTER_KEY_LABEL_HYBRID,
					theirStaticPublicKey,
					ourStaticKeyPair,
					kemSecret,
					inputs);
		} else {
			return crypto.deriveHybridSharedSecret(
					MASTER_KEY_LABEL_HYBRID,
					theirStaticPublicKey,
					ourStaticKeyPair,
					kemCiphertext,
					inputs);
		}
	}

	@Override
	public SecretKey deriveHybridMasterKeyFs(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, byte[] kemCiphertext,
			byte[] kemSecret, boolean alice, byte ourMinor, byte theirMinor)
			throws GeneralSecurityException {
		byte[] theirStatic = theirStaticPublicKey.getEncoded();
		byte[] theirEphemeral = theirEphemeralPublicKey.getEncoded();
		byte[] ourStatic = ourStaticKeyPair.getPublic().getEncoded();
		byte[] ourEphemeral = ourEphemeralKeyPair.getPublic().getEncoded();
		byte[][] inputs = {
				alice ? ourStatic : theirStatic,
				alice ? theirStatic : ourStatic,
				alice ? ourEphemeral : theirEphemeral,
				alice ? theirEphemeral : ourEphemeral,
				kemCiphertext,
				new byte[] {alice ? ourMinor : theirMinor},
				new byte[] {alice ? theirMinor : ourMinor}
		};
		if (alice) {
			return crypto.deriveHybridSharedSecretFsAsResponder(
					MASTER_KEY_LABEL_HYBRID_FS,
					theirStaticPublicKey, theirEphemeralPublicKey,
					ourStaticKeyPair, ourEphemeralKeyPair,
					kemSecret, inputs);
		} else {
			return crypto.deriveHybridSharedSecretFs(
					MASTER_KEY_LABEL_HYBRID_FS,
					theirStaticPublicKey, theirEphemeralPublicKey,
					ourStaticKeyPair, ourEphemeralKeyPair,
					kemCiphertext, inputs);
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

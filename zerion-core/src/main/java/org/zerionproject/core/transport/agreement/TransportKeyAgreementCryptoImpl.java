package org.zerionproject.core.transport.agreement;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.core.api.Bytes.compare;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.ROOT_KEY_LABEL;

@Immutable
@NotNullByDefault
class TransportKeyAgreementCryptoImpl implements TransportKeyAgreementCrypto {

	private final CryptoComponent crypto;

	@Inject
	TransportKeyAgreementCryptoImpl(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	@Override
	public KeyPair generateKeyPair() {
		return crypto.generateAgreementKeyPair();
	}

	@Override
	public SecretKey deriveRootKey(KeyPair localKeyPair,
			PublicKey remotePublicKey) throws GeneralSecurityException {
		byte[] theirPublic = remotePublicKey.getEncoded();
		byte[] ourPublic = localKeyPair.getPublic().getEncoded();
		boolean alice = compare(ourPublic, theirPublic) < 0;
		byte[][] inputs = {
				alice ? ourPublic : theirPublic,
				alice ? theirPublic : ourPublic
		};
		return crypto.deriveSharedSecret(ROOT_KEY_LABEL, remotePublicKey,
				localKeyPair, inputs);
	}

	@Override
	public PublicKey parsePublicKey(byte[] encoded) throws FormatException {
		try {
			return crypto.getAgreementKeyParser().parsePublicKey(encoded);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
	}

	@Override
	public PrivateKey parsePrivateKey(byte[] encoded) throws FormatException {
		try {
			return crypto.getAgreementKeyParser().parsePrivateKey(encoded);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
	}
}

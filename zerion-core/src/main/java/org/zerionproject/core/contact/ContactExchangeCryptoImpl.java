package org.zerionproject.core.contact;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.inject.Inject;

import static org.zerionproject.core.contact.ContactExchangeConstants.ALICE_KEY_LABEL;
import static org.zerionproject.core.contact.ContactExchangeConstants.ALICE_NONCE_LABEL;
import static org.zerionproject.core.contact.ContactExchangeConstants.BOB_KEY_LABEL;
import static org.zerionproject.core.contact.ContactExchangeConstants.BOB_NONCE_LABEL;
import static org.zerionproject.core.contact.ContactExchangeConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.contact.ContactExchangeConstants.SIGNING_LABEL;

@NotNullByDefault
class ContactExchangeCryptoImpl implements ContactExchangeCrypto {

	private static final byte[] PROTOCOL_VERSION_BYTES =
			new byte[] {PROTOCOL_VERSION};

	private final CryptoComponent crypto;

	@Inject
	ContactExchangeCryptoImpl(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	@Override
	public SecretKey deriveHeaderKey(SecretKey masterKey, boolean alice) {
		String label = alice ? ALICE_KEY_LABEL : BOB_KEY_LABEL;
		return crypto.deriveKey(label, masterKey, PROTOCOL_VERSION_BYTES);
	}

	@Override
	public byte[] sign(PrivateKey privateKey, SecretKey masterKey,
			boolean alice) {
		byte[] nonce = deriveNonce(masterKey, alice);
		try {
			return crypto.sign(SIGNING_LABEL, nonce, privateKey);
		} catch (GeneralSecurityException e) {
			throw new AssertionError();
		}
	}

	@Override
	public boolean verify(PublicKey publicKey,
			SecretKey masterKey, boolean alice, byte[] signature) {
		byte[] nonce = deriveNonce(masterKey, alice);
		try {
			return crypto.verifySignature(signature, SIGNING_LABEL, nonce,
					publicKey);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	private byte[] deriveNonce(SecretKey masterKey, boolean alice) {
		String label = alice ? ALICE_NONCE_LABEL : BOB_NONCE_LABEL;
		return crypto.mac(label, masterKey, PROTOCOL_VERSION_BYTES);
	}
}

package org.zerionproject.core.plugin.tor;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.util.encoders.Base64;
import org.zerionproject.core.api.crypto.CryptoComponent;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.zerionproject.core.util.StringUtils.US_ASCII;

public class TorRendezvousCryptoImpl implements TorRendezvousCrypto {

	private final CryptoComponent crypto;

	public TorRendezvousCryptoImpl(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	@Override
	public String getOnion(byte[] seed) {
		byte[] publicKey = new Ed25519PrivateKeyParameters(seed, 0)
				.generatePublicKey().getEncoded();
		return crypto.encodeOnion(publicKey);
	}

	@Override
	public String getPrivateKeyBlob(byte[] seed) {
		byte[] h = sha512(seed);
		// Ed25519 scalar clamping (RFC 8032): the resulting 64-byte expanded
		// key is Tor's ED25519-V3 secret key format.
		h[0] &= (byte) 248;
		h[31] &= (byte) 127;
		h[31] |= (byte) 64;
		byte[] base64 = Base64.encode(h);
		return "ED25519-V3:" + new String(base64, US_ASCII);
	}

	private static byte[] sha512(byte[] input) {
		try {
			return MessageDigest.getInstance("SHA-512").digest(input);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}
}

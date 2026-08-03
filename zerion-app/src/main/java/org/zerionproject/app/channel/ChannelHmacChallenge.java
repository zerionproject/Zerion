package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.SecureRandom;

import javax.inject.Inject;

@NotNullByDefault
class ChannelHmacChallenge {

	private static final String LABEL =
			"org.zerionproject/CHANNEL_HMAC_CHALLENGE";

	private final CryptoComponent crypto;
	private final SecureRandom random;

	@Inject
	ChannelHmacChallenge(CryptoComponent crypto) {
		this.crypto = crypto;
		this.random = new SecureRandom();
	}

	byte[] freshNonce() {
		byte[] n = new byte[(int)
				ChannelConstants.BOOTSTRAP_HMAC_NONCE_BYTES];
		random.nextBytes(n);
		return n;
	}

	byte[] respond(byte[] capability, byte[] nonce, byte[] channelId) {
		SecretKey k = new SecretKey(capability);
		return crypto.mac(LABEL, k, channelId, nonce);
	}

	boolean verify(byte[] capability, byte[] nonce, byte[] channelId,
			byte[] response) {
		SecretKey k = new SecretKey(capability);
		return crypto.verifyMac(response, LABEL, k, channelId, nonce);
	}
}

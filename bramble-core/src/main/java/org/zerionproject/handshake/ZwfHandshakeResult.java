package org.zerionproject.handshake;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Output of a successful Zerion handshake. The {@code rootKey} is the shared
 * contact root key from which the transport tag/header keys and the Mode 3-Full
 * ratchet state are derived; {@code alice} is the agreed role tiebreaker (both
 * sides compute the opposite value), used to keep every subsequent derivation
 * directionally consistent.
 */
@NotNullByDefault
public final class ZwfHandshakeResult {

	private final SecretKey rootKey;
	private final boolean alice;
	private final boolean mode3Capable;
	private final byte[] ourStaticPublicKey;
	private final byte[] theirStaticPublicKey;
	private final byte[] ourEphemeralX25519;
	private final byte[] theirEphemeralX25519;

	ZwfHandshakeResult(SecretKey rootKey, boolean alice, boolean mode3Capable,
			byte[] ourStaticPublicKey, byte[] theirStaticPublicKey,
			byte[] ourEphemeralX25519, byte[] theirEphemeralX25519) {
		this.rootKey = rootKey;
		this.alice = alice;
		this.mode3Capable = mode3Capable;
		this.ourStaticPublicKey = ourStaticPublicKey;
		this.theirStaticPublicKey = theirStaticPublicKey;
		this.ourEphemeralX25519 = ourEphemeralX25519;
		this.theirEphemeralX25519 = theirEphemeralX25519;
	}

	public SecretKey getRootKey() {
		return rootKey;
	}

	public boolean isAlice() {
		return alice;
	}

	public boolean isMode3Capable() {
		return mode3Capable;
	}

	public byte[] getOurStaticPublicKey() {
		return ourStaticPublicKey;
	}

	public byte[] getTheirStaticPublicKey() {
		return theirStaticPublicKey;
	}

	public byte[] getOurEphemeralX25519() {
		return ourEphemeralX25519;
	}

	public byte[] getTheirEphemeralX25519() {
		return theirEphemeralX25519;
	}
}

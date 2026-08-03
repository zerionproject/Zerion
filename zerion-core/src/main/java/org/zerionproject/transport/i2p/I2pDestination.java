package org.zerionproject.transport.i2p;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * The result of starting the I2P transport: the public {@code destination} to
 * publish for peers to dial, and the {@code privateKey} to persist so the same
 * destination is recreated on the next run.
 */
@NotNullByDefault
public class I2pDestination {

	private final String destination;
	private final String privateKey;

	public I2pDestination(String destination, String privateKey) {
		this.destination = destination;
		this.privateKey = privateKey;
	}

	public String getDestination() {
		return destination;
	}

	public String getPrivateKey() {
		return privateKey;
	}
}

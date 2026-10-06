package org.zerionproject.transport.i2p;

import org.briarproject.nullsafety.NotNullByDefault;

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

package org.zerionproject.core.plugin.tor.auth;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.Collection;

import javax.annotation.Nullable;

/**
 * The Tor control operations client authorization needs. Implemented over
 * the transport's control connection and attached to the manager by the
 * Tor plugin while Tor runs.
 */
@NotNullByDefault
public interface OnionServiceControl {

	final class Published {

		public final String onion;
		public final String privateKey;

		public Published(String onion, String privateKey) {
			this.onion = onion;
			this.privateKey = privateKey;
		}
	}

	/** Thrown when Tor reports that no more authorized clients fit. */
	final class CapacityException extends IOException {

		public CapacityException() {
			super("authorized client capacity reached");
		}
	}

	Published publish(@Nullable String privateKey, int localPort,
			int remotePort, Collection<byte[]> clientPublicKeys)
			throws IOException;

	void remove(String onion) throws IOException;

	void addClientKey(String onion, byte[] clientPrivateKey)
			throws IOException;

	void removeClientKey(String onion) throws IOException;
}

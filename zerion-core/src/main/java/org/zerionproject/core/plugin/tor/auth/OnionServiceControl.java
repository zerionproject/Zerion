package org.zerionproject.core.plugin.tor.auth;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.plugin.OnionTargets;

import java.io.IOException;
import java.util.Collection;

import javax.annotation.Nullable;

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

	final class CapacityException extends IOException {

		public CapacityException() {
			super("authorized client capacity reached");
		}
	}

	Published publish(@Nullable String privateKey, int localPort,
			int remotePort, Collection<byte[]> clientPublicKeys)
			throws IOException;

	default Published publish(@Nullable String privateKey, String target,
			int remotePort, Collection<byte[]> clientPublicKeys)
			throws IOException {
		int port = OnionTargets.loopbackPort(target);
		if (port <= 0) throw new IOException("Unsupported onion target");
		return publish(privateKey, port, remotePort, clientPublicKeys);
	}

	void remove(String onion) throws IOException;

	void addClientKey(String onion, byte[] clientPrivateKey)
			throws IOException;

	void removeClientKey(String onion) throws IOException;
}

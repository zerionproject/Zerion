package org.zerionproject.transport.i2p;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * An {@link I2pRouter} that relies on an I2P router run outside the app, for
 * example the standalone i2pd Android app or an i2pd on the emulator host, with
 * its SAM bridge enabled. {@link #start} only probes that the bridge is
 * reachable; it does not start or stop the router. This is the path for testing
 * the I2P transport before a native router is bundled.
 */
@NotNullByDefault
public class ExternalI2pRouter implements I2pRouter {

	private final String samHost;
	private final int samPort;
	private final int probeTimeoutMs;

	public ExternalI2pRouter(String samHost, int samPort,
			int probeTimeoutMs) {
		this.samHost = samHost;
		this.samPort = samPort;
		this.probeTimeoutMs = probeTimeoutMs;
	}

	@Override
	public void start() throws IOException {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(samHost, samPort),
					probeTimeoutMs);
		}
	}

	@Override
	public void stop() {
		// The router is external; not ours to stop.
	}
}

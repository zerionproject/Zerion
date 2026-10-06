package org.zerionproject.transport.i2p;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

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
	}
}

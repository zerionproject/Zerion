package com.professor.zerion.android.vault.net;

import org.zerionproject.core.socks.TorSocksConnector;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.Socket;

import javax.annotation.Nullable;

/**
 * The wallet code's way to Tor's SOCKS listener. The listener is a Unix
 * domain socket that only this process can open, so the wallet helpers ask
 * here for a stream instead of dialling a loopback port. Until the platform
 * has installed the connector nothing can be opened: there is no loopback
 * fallback to fail open onto. The port argument the helpers still carry is
 * only their readiness flag.
 */
@NotNullByDefault
public final class TorSockets {

	@Nullable
	private static volatile TorSocksConnector connector;

	private TorSockets() {
	}

	public static void install(TorSocksConnector c) {
		connector = c;
	}

	public static Socket open(int socksPort, int timeoutMs)
			throws IOException {
		if (socksPort <= 0) throw new IOException("Tor is not ready");
		TorSocksConnector c = connector;
		if (c == null) throw new IOException("Tor proxy not available");
		Socket s = c.openProxySocket(timeoutMs);
		s.setSoTimeout(timeoutMs);
		return s;
	}
}

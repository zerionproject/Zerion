package com.professor.zerion.android.vault.net;

import org.zerionproject.core.socks.TorSocksConnector;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.Socket;

import javax.annotation.Nullable;

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

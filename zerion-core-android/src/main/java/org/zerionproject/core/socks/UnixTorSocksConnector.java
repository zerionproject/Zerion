package org.zerionproject.core.socks;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.net.Socket;

@NotNullByDefault
public final class UnixTorSocksConnector implements TorSocksConnector {

	private final File path;

	public UnixTorSocksConnector(File path) {
		this.path = path;
	}

	@Override
	public Socket openProxySocket(int connectTimeoutMs) throws IOException {
		return LocalSockets.connect(path.getAbsolutePath(), connectTimeoutMs);
	}
}

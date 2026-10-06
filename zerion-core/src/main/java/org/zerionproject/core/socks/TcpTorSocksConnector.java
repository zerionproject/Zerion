package org.zerionproject.core.socks;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

@NotNullByDefault
public final class TcpTorSocksConnector implements TorSocksConnector {

	private final InetSocketAddress proxy;

	public TcpTorSocksConnector(InetSocketAddress proxy) {
		this.proxy = proxy;
	}

	@Override
	public Socket openProxySocket(int connectTimeoutMs) throws IOException {
		Socket s = new Socket();
		try {
			s.connect(proxy, connectTimeoutMs);
		} catch (IOException e) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
			throw e;
		}
		return s;
	}
}

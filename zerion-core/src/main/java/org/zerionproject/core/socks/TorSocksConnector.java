package org.zerionproject.core.socks;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.Socket;

@NotNullByDefault
public interface TorSocksConnector {

	Socket openProxySocket(int connectTimeoutMs) throws IOException;
}

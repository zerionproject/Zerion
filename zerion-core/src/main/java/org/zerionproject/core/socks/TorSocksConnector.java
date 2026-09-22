package org.zerionproject.core.socks;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.Socket;

/**
 * Opens a raw stream to the local Tor SOCKS listener. On Android the
 * listener is a Unix domain socket inside the app's private directory, so
 * no other process can reach it; on a plain JVM it is a loopback TCP port.
 * The SOCKS handshake itself is spoken by {@link TorSocksSocket} over the
 * returned stream.
 */
@NotNullByDefault
public interface TorSocksConnector {

	Socket openProxySocket(int connectTimeoutMs) throws IOException;
}

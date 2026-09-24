package org.zerionproject.core.socks;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.net.Socket;

/**
 * Reaches Tor's SOCKS listener over the Unix domain socket Tor binds inside
 * the app's private directory. The directory is readable by this app's uid
 * only, so no other process on the device can open the listener; the
 * connect timeout is enforced by the caller's read timeout on the returned
 * stream, since a local socket connect either succeeds at once or fails.
 */
@NotNullByDefault
public final class UnixTorSocksConnector implements TorSocksConnector {

	private final File path;

	public UnixTorSocksConnector(File path) {
		this.path = path;
	}

	@Override
	public Socket openProxySocket(int connectTimeoutMs) throws IOException {
		LocalSocket local = new LocalSocket(LocalSocket.SOCKET_STREAM);
		try {
			local.connect(new LocalSocketAddress(path.getAbsolutePath(),
					LocalSocketAddress.Namespace.FILESYSTEM));
			local.setSoTimeout(connectTimeoutMs);
		} catch (IOException e) {
			try {
				local.close();
			} catch (IOException ignored) {
			}
			throw e;
		}
		return new LocalStreamSocket(local);
	}
}

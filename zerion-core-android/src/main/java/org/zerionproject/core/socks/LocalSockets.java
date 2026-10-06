package org.zerionproject.core.socks;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.Socket;

@NotNullByDefault
public final class LocalSockets {

	private LocalSockets() {
	}

	public static Socket connect(String path, int readTimeoutMs)
			throws IOException {
		LocalSocket local = new LocalSocket(LocalSocket.SOCKET_STREAM);
		try {
			local.connect(new LocalSocketAddress(path,
					LocalSocketAddress.Namespace.FILESYSTEM));
			local.setSoTimeout(readTimeoutMs);
		} catch (IOException e) {
			try {
				local.close();
			} catch (IOException ignored) {
			}
			throw e;
		}
		return new LocalStreamSocket(local);
	}

	static Socket wrap(LocalSocket local) {
		return new LocalStreamSocket(local);
	}
}

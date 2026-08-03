package org.zerionproject.transport.lan;

import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.duplex.AbstractDuplexTransportConnection;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import javax.annotation.concurrent.ThreadSafe;

/**
 * A duplex connection over a local point-to-point TCP socket, used only to
 * carry the offline pairing key agreement and contact exchange. Modelled on
 * Briar's TcpTransportConnection.
 */
@ThreadSafe
@NotNullByDefault
class LanKeyAgreementConnection extends AbstractDuplexTransportConnection {

	private final Socket socket;

	LanKeyAgreementConnection(Plugin plugin, Socket socket) {
		super(plugin);
		this.socket = socket;
	}

	@Override
	protected InputStream getInputStream() throws IOException {
		return socket.getInputStream();
	}

	@Override
	protected OutputStream getOutputStream() throws IOException {
		return socket.getOutputStream();
	}

	@Override
	protected void closeConnection(boolean exception) throws IOException {
		socket.close();
	}
}

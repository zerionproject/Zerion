package org.zerionproject.core.socks;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

/**
 * A socket that reaches its destination through the local Tor SOCKS
 * listener. The stream to the listener comes from a
 * {@link TorSocksConnector}, so the same client works over a Unix domain
 * socket on Android and over loopback TCP elsewhere; this class speaks
 * SOCKS5 with username/password authentication over that stream and then
 * hands every read and write to it. The destination is always sent as a
 * domain name: nothing here resolves a name, so an onion address never
 * reaches the system resolver.
 */
@NotNullByDefault
public class TorSocksSocket extends Socket {

	private static final int MAX_HOST_LENGTH = 255;
	private static final String[] ERRORS = {
			"Succeeded",
			"General SOCKS server failure",
			"Connection not allowed by ruleset",
			"Network unreachable",
			"Host unreachable",
			"Connection refused",
			"TTL expired",
			"Command not supported",
			"Address type not supported"
	};

	private final TorSocksConnector connector;
	private final int connectToProxyTimeout;
	private final int extraConnectTimeout;
	private final int extraSocketTimeout;
	private final String username;
	private final String password;

	@Nullable
	private volatile Socket raw;
	private int pendingSoTimeout = 0;
	private boolean closed = false;

	public TorSocksSocket(TorSocksConnector connector,
			int connectToProxyTimeout, int extraConnectTimeout,
			int extraSocketTimeout, String username, String password) {
		this.connector = connector;
		this.connectToProxyTimeout = connectToProxyTimeout;
		this.extraConnectTimeout = extraConnectTimeout;
		this.extraSocketTimeout = extraSocketTimeout;
		this.username = username;
		this.password = password;
	}

	@Override
	public void connect(SocketAddress endpoint) throws IOException {
		connect(endpoint, 0);
	}

	@Override
	public void connect(SocketAddress endpoint, int timeout)
			throws IOException {
		if (!(endpoint instanceof InetSocketAddress)) {
			throw new IllegalArgumentException();
		}
		InetSocketAddress inet = (InetSocketAddress) endpoint;
		String host = inet.getHostString();
		if (host.length() > MAX_HOST_LENGTH || host.isEmpty()) {
			throw new IllegalArgumentException();
		}
		int port = inet.getPort();
		synchronized (this) {
			if (closed) throw new SocketException("Socket is closed");
			if (raw != null) throw new SocketException("already connected");
		}
		Socket proxy = connector.openProxySocket(connectToProxyTimeout);
		try {
			int oldTimeout = pendingSoTimeout;
			proxy.setSoTimeout(timeout + extraConnectTimeout);
			OutputStream out = proxy.getOutputStream();
			InputStream in = proxy.getInputStream();
			sendMethodRequest(out);
			receiveMethodResponse(in);
			sendAuthRequest(out);
			receiveAuthResponse(in);
			sendConnectRequest(out, host, port);
			receiveConnectResponse(in);
			proxy.setSoTimeout(oldTimeout + extraSocketTimeout);
		} catch (IOException | RuntimeException e) {
			try {
				proxy.close();
			} catch (IOException ignored) {
			}
			throw e;
		}
		synchronized (this) {
			if (closed) {
				try {
					proxy.close();
				} catch (IOException ignored) {
				}
				throw new SocketException("Socket is closed");
			}
			raw = proxy;
		}
	}

	private void sendMethodRequest(OutputStream out) throws IOException {
		out.write(new byte[] {5, 1, 2});
		out.flush();
	}

	private void receiveMethodResponse(InputStream in) throws IOException {
		byte[] r = readFully(in, 2);
		if (r[0] != 5) throw new IOException("Unsupported SOCKS version");
		if (r[1] != 2) throw new IOException("Unsupported auth method");
	}

	private void sendAuthRequest(OutputStream out) throws IOException {
		byte[] u = username.getBytes(StandardCharsets.UTF_8);
		byte[] p = password.getBytes(StandardCharsets.UTF_8);
		if (u.length > 255 || p.length > 255 || u.length == 0) {
			throw new IOException("SOCKS credential length");
		}
		byte[] req = new byte[3 + u.length + p.length];
		req[0] = 1;
		req[1] = (byte) u.length;
		System.arraycopy(u, 0, req, 2, u.length);
		req[2 + u.length] = (byte) p.length;
		System.arraycopy(p, 0, req, 3 + u.length, p.length);
		out.write(req);
		out.flush();
	}

	private void receiveAuthResponse(InputStream in) throws IOException {
		byte[] r = readFully(in, 2);
		if (r[0] != 1) throw new IOException("Unsupported subnegotiation");
		if (r[1] != 0) throw new IOException("Authentication failed");
	}

	private void sendConnectRequest(OutputStream out, String host, int port)
			throws IOException {
		byte[] h = host.getBytes(StandardCharsets.US_ASCII);
		byte[] req = new byte[7 + h.length];
		req[0] = 5;
		req[1] = 1;
		req[2] = 0;
		req[3] = 3;
		req[4] = (byte) h.length;
		System.arraycopy(h, 0, req, 5, h.length);
		req[5 + h.length] = (byte) ((port >> 8) & 0xFF);
		req[6 + h.length] = (byte) (port & 0xFF);
		out.write(req);
		out.flush();
	}

	private void receiveConnectResponse(InputStream in) throws IOException {
		byte[] r = readFully(in, 4);
		if ((r[0] & 0xFF) != 5) throw new IOException("Unsupported version");
		int reply = r[1] & 0xFF;
		if (reply != 0) {
			throw new IOException("Connection failed: "
					+ (reply < ERRORS.length ? ERRORS[reply]
					: String.valueOf(reply)));
		}
		int type = r[3] & 0xFF;
		if (type == 1) readFully(in, 4);
		else if (type == 4) readFully(in, 16);
		else if (type == 3) readFully(in, readFully(in, 1)[0] & 0xFF);
		else throw new IOException("Unsupported address type");
		readFully(in, 2);
	}

	private static byte[] readFully(InputStream in, int n)
			throws IOException {
		byte[] b = new byte[n];
		int off = 0;
		while (off < n) {
			int r = in.read(b, off, n - off);
			if (r < 0) throw new IOException("Unexpected end of stream");
			off += r;
		}
		return b;
	}

	private Socket connected() throws SocketException {
		Socket s = raw;
		if (s == null) throw new SocketException("Socket is not connected");
		return s;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return connected().getInputStream();
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return connected().getOutputStream();
	}

	@Override
	public synchronized void setSoTimeout(int timeout) throws SocketException {
		Socket s = raw;
		if (s == null) pendingSoTimeout = timeout;
		else s.setSoTimeout(timeout);
	}

	@Override
	public synchronized int getSoTimeout() throws SocketException {
		Socket s = raw;
		return s == null ? pendingSoTimeout : s.getSoTimeout();
	}

	@Override
	public void setTcpNoDelay(boolean on) throws SocketException {
		Socket s = raw;
		if (s != null) s.setTcpNoDelay(on);
	}

	@Override
	public void setKeepAlive(boolean on) throws SocketException {
		Socket s = raw;
		if (s != null) s.setKeepAlive(on);
	}

	@Override
	public void shutdownInput() throws IOException {
		connected().shutdownInput();
	}

	@Override
	public void shutdownOutput() throws IOException {
		connected().shutdownOutput();
	}

	@Override
	public boolean isInputShutdown() {
		Socket s = raw;
		return s != null && s.isInputShutdown();
	}

	@Override
	public boolean isOutputShutdown() {
		Socket s = raw;
		return s != null && s.isOutputShutdown();
	}

	@Override
	public boolean isConnected() {
		return raw != null;
	}

	@Override
	public boolean isBound() {
		return raw != null;
	}

	@Override
	public synchronized boolean isClosed() {
		return closed;
	}

	@Override
	@Nullable
	public SocketAddress getRemoteSocketAddress() {
		return null;
	}

	@Override
	public synchronized void close() throws IOException {
		if (closed) return;
		closed = true;
		Socket s = raw;
		if (s != null) s.close();
		super.close();
	}

	@Override
	public String toString() {
		return "TorSocksSocket";
	}
}

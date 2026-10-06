package org.zerionproject.core.socks;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Locale;

import javax.net.SocketFactory;

@NotNullByDefault
public class IsolatingSocksSocketFactory extends SocketFactory {

	private static final int RANDOM_USERNAME_BYTES = 16;

	private final TorSocksConnector connector;
	private final int connectToProxyTimeout;
	private final int extraConnectTimeout;
	private final int extraSocketTimeout;
	private final SecureRandom random;
	private final String password;

	public IsolatingSocksSocketFactory(TorSocksConnector connector,
			int connectToProxyTimeout, int extraConnectTimeout,
			int extraSocketTimeout, SecureRandom random,
			SocksIsolationSecret secret) {
		this.connector = connector;
		this.connectToProxyTimeout = connectToProxyTimeout;
		this.extraConnectTimeout = extraConnectTimeout;
		this.extraSocketTimeout = extraSocketTimeout;
		this.random = random;
		this.password = secret.value();
	}

	static String usernameFor(String host) {
		return host.toLowerCase(Locale.US);
	}

	private String randomToken(int bytes) {
		byte[] b = new byte[bytes];
		random.nextBytes(b);
		return StringUtils.toHexString(b);
	}

	private TorSocksSocket socketFor(String username) {
		return new TorSocksSocket(connector, connectToProxyTimeout,
				extraConnectTimeout, extraSocketTimeout, username, password);
	}

	private Socket connect(String host, int port) throws IOException {
		Socket s = socketFor(usernameFor(host));
		try {
			s.connect(InetSocketAddress.createUnresolved(host, port));
		} catch (IOException | RuntimeException e) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
			throw e;
		}
		return s;
	}

	@Override
	public Socket createSocket() {
		return socketFor(randomToken(RANDOM_USERNAME_BYTES));
	}

	@Override
	public Socket createSocket(String host, int port) throws IOException {
		return connect(host, port);
	}

	@Override
	public Socket createSocket(InetAddress host, int port)
			throws IOException {
		return connect(host.getHostAddress(), port);
	}

	@Override
	public Socket createSocket(String host, int port, InetAddress localHost,
			int localPort) throws IOException {
		return connect(host, port);
	}

	@Override
	public Socket createSocket(InetAddress address, int port,
			InetAddress localAddress, int localPort) throws IOException {
		return connect(address.getHostAddress(), port);
	}
}

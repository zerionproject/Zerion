package org.zerionproject.core.socks;

import org.briarproject.nullsafety.NotNullByDefault;
import org.briarproject.socks.SocksSocketFactory;
import org.zerionproject.core.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Locale;

import javax.net.SocketFactory;

/**
 * Socket factory for the local Tor SOCKS port that gives every destination
 * its own Tor circuit. Tor isolates streams by their SOCKS username and
 * password (its IsolateSOCKSAuth default), so every connection authenticates
 * with the destination host as the username and a password drawn once per
 * process. Connections to one destination share a circuit, connections to
 * different destinations never do, and a socket created without a
 * destination receives a fresh random username so that it is isolated on its
 * own. The credentials carry no secret: Tor ignores their value and only
 * compares them, so they are an isolation key, not an authentication. They
 * only take effect on a SOCKS listener with IsolateSOCKSAuth, which
 * {@link org.zerionproject.transport.TorPrivacyConfigurator} enforces.
 */
@NotNullByDefault
public class IsolatingSocksSocketFactory extends SocketFactory {

	private static final int RANDOM_USERNAME_BYTES = 16;

	private final InetSocketAddress proxy;
	private final int connectToProxyTimeout;
	private final int extraConnectTimeout;
	private final int extraSocketTimeout;
	private final SecureRandom random;
	private final String password;

	public IsolatingSocksSocketFactory(InetSocketAddress proxy,
			int connectToProxyTimeout, int extraConnectTimeout,
			int extraSocketTimeout, SecureRandom random,
			SocksIsolationSecret secret) {
		this.proxy = proxy;
		this.connectToProxyTimeout = connectToProxyTimeout;
		this.extraConnectTimeout = extraConnectTimeout;
		this.extraSocketTimeout = extraSocketTimeout;
		this.random = random;
		this.password = secret.value();
	}

	/**
	 * The SOCKS username that isolates connections to the given host. The
	 * host is case-folded so that two spellings of one onion address share a
	 * circuit rather than opening two.
	 */
	static String usernameFor(String host) {
		return host.toLowerCase(Locale.US);
	}

	private String randomToken(int bytes) {
		byte[] b = new byte[bytes];
		random.nextBytes(b);
		return StringUtils.toHexString(b);
	}

	private SocksSocketFactory delegate(String username) {
		return new SocksSocketFactory(proxy, connectToProxyTimeout,
				extraConnectTimeout, extraSocketTimeout, username, password);
	}

	@Override
	public Socket createSocket() {
		return delegate(randomToken(RANDOM_USERNAME_BYTES)).createSocket();
	}

	@Override
	public Socket createSocket(String host, int port) throws IOException {
		return delegate(usernameFor(host)).createSocket(host, port);
	}

	@Override
	public Socket createSocket(InetAddress host, int port)
			throws IOException {
		return delegate(usernameFor(host.getHostAddress()))
				.createSocket(host, port);
	}

	@Override
	public Socket createSocket(String host, int port, InetAddress localHost,
			int localPort) throws IOException {
		return delegate(usernameFor(host))
				.createSocket(host, port, localHost, localPort);
	}

	@Override
	public Socket createSocket(InetAddress address, int port,
			InetAddress localAddress, int localPort) throws IOException {
		return delegate(usernameFor(address.getHostAddress()))
				.createSocket(address, port, localAddress, localPort);
	}
}

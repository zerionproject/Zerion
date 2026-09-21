package org.zerionproject.core.socks;

import org.zerionproject.core.api.plugin.FastConnectSocketFactory;
import org.zerionproject.core.api.plugin.TorSocksPort;

import java.net.InetSocketAddress;
import java.security.SecureRandom;

import javax.net.SocketFactory;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.zerionproject.core.api.plugin.TorConstants.CONNECT_TO_PROXY_TIMEOUT;
import static org.zerionproject.core.api.plugin.TorConstants.EXTRA_CONNECT_TIMEOUT;
import static org.zerionproject.core.api.plugin.TorConstants.EXTRA_SOCKET_TIMEOUT;
import static org.zerionproject.core.api.plugin.TorConstants.FAST_CONNECT_TIMEOUT;

@Module
public class SocksModule {

	@Provides
	@Singleton
	SocksIsolationSecret provideSocksIsolationSecret() {
		return new SocksIsolationSecret(new SecureRandom());
	}

	@Provides
	SocketFactory provideTorSocketFactory(@TorSocksPort int torSocksPort,
			SocksIsolationSecret secret) {
		InetSocketAddress proxy = new InetSocketAddress("127.0.0.1",
				torSocksPort);
		return new IsolatingSocksSocketFactory(proxy, CONNECT_TO_PROXY_TIMEOUT,
				EXTRA_CONNECT_TIMEOUT, EXTRA_SOCKET_TIMEOUT, new SecureRandom(),
				secret);
	}

	@Provides
	@FastConnectSocketFactory
	SocketFactory provideFastTorSocketFactory(@TorSocksPort int torSocksPort,
			SocksIsolationSecret secret) {
		InetSocketAddress proxy = new InetSocketAddress("127.0.0.1",
				torSocksPort);
		return new IsolatingSocksSocketFactory(proxy, CONNECT_TO_PROXY_TIMEOUT,
				FAST_CONNECT_TIMEOUT, EXTRA_SOCKET_TIMEOUT, new SecureRandom(),
				secret);
	}
}

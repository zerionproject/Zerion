package org.zerionproject.core.socks;

import org.zerionproject.core.api.plugin.FastConnectSocketFactory;

import java.security.SecureRandom;

import javax.net.SocketFactory;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.zerionproject.core.api.plugin.TorConstants.CONNECT_TO_PROXY_TIMEOUT;
import static org.zerionproject.core.api.plugin.TorConstants.EXTRA_CONNECT_TIMEOUT;
import static org.zerionproject.core.api.plugin.TorConstants.EXTRA_SOCKET_TIMEOUT;
import static org.zerionproject.core.api.plugin.TorConstants.FAST_CONNECT_TIMEOUT;

/**
 * Socket factories for the local Tor SOCKS listener. The platform binds the
 * {@link TorSocksConnector} that reaches the listener; everything above it
 * only speaks SOCKS over whatever stream the connector opens.
 */
@Module
public class SocksModule {

	@Provides
	@Singleton
	SocksIsolationSecret provideSocksIsolationSecret() {
		return new SocksIsolationSecret(new SecureRandom());
	}

	@Provides
	SocketFactory provideTorSocketFactory(TorSocksConnector connector,
			SocksIsolationSecret secret) {
		return new IsolatingSocksSocketFactory(connector,
				CONNECT_TO_PROXY_TIMEOUT, EXTRA_CONNECT_TIMEOUT,
				EXTRA_SOCKET_TIMEOUT, new SecureRandom(), secret);
	}

	@Provides
	@FastConnectSocketFactory
	SocketFactory provideFastTorSocketFactory(TorSocksConnector connector,
			SocksIsolationSecret secret) {
		return new IsolatingSocksSocketFactory(connector,
				CONNECT_TO_PROXY_TIMEOUT, FAST_CONNECT_TIMEOUT,
				EXTRA_SOCKET_TIMEOUT, new SecureRandom(), secret);
	}
}

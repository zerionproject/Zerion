package org.zerionproject.core.socks;

import org.zerionproject.core.api.plugin.FastConnectSocketFactory;
import org.zerionproject.core.api.plugin.TorSocksPort;
import org.briarproject.socks.SocksSocketFactory;

import java.net.InetSocketAddress;

import javax.net.SocketFactory;

import dagger.Module;
import dagger.Provides;

import static org.zerionproject.core.api.plugin.TorConstants.CONNECT_TO_PROXY_TIMEOUT;
import static org.zerionproject.core.api.plugin.TorConstants.EXTRA_CONNECT_TIMEOUT;
import static org.zerionproject.core.api.plugin.TorConstants.EXTRA_SOCKET_TIMEOUT;
import static org.zerionproject.core.api.plugin.TorConstants.FAST_CONNECT_TIMEOUT;

@Module
public class SocksModule {

	@Provides
	SocketFactory provideTorSocketFactory(@TorSocksPort int torSocksPort) {
		InetSocketAddress proxy = new InetSocketAddress("127.0.0.1",
				torSocksPort);
		return new SocksSocketFactory(proxy, CONNECT_TO_PROXY_TIMEOUT,
				EXTRA_CONNECT_TIMEOUT, EXTRA_SOCKET_TIMEOUT);
	}

	@Provides
	@FastConnectSocketFactory
	SocketFactory provideFastTorSocketFactory(@TorSocksPort int torSocksPort) {
		InetSocketAddress proxy = new InetSocketAddress("127.0.0.1",
				torSocksPort);
		return new SocksSocketFactory(proxy, CONNECT_TO_PROXY_TIMEOUT,
				FAST_CONNECT_TIMEOUT, EXTRA_SOCKET_TIMEOUT);
	}
}

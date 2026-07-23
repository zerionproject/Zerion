package com.professor.zerion.android;

import org.briarproject.onionwrapper.TorWrapper;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.FastConnectSocketFactory;
import org.zerionproject.transport.ZtpConnectionHandler;
import org.zerionproject.transport.ZtpTorTransport;

import java.util.concurrent.Executor;

import javax.inject.Singleton;
import javax.net.SocketFactory;

import dagger.Module;
import dagger.Provides;

@Module
public class ZerionTorModule {

	@Provides
	@Singleton
	ZtpTorTransport provideTorTransport(TorWrapper tor,
			SocketFactory torSocketFactory,
			@FastConnectSocketFactory SocketFactory fastSocketFactory,
			@IoExecutor Executor ioExecutor, ZtpConnectionHandler handler) {
		return new ZtpTorTransport(tor, torSocketFactory, fastSocketFactory,
				ioExecutor, handler);
	}
}

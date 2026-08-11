package com.professor.zerion.android.i2p;

import android.content.Context;

import org.zerionproject.core.api.plugin.TorSocksPort;
import org.zerionproject.transport.ZtpConnectionHandler;
import org.zerionproject.transport.i2p.I2pOverlayTransport;
import org.zerionproject.transport.i2p.I2pStack;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

@NotNullByDefault
public class BundledI2pStack implements I2pStack {

	private final Context context;
	private final int torSocksPort;

	@Inject
	public BundledI2pStack(Context context, @TorSocksPort int torSocksPort) {
		this.context = context;
		this.torSocksPort = torSocksPort;
	}

	@Override
	public I2pOverlayTransport createTransport(Executor ioExecutor,
			ZtpConnectionHandler handler) {
		BundledI2pRouter router = new BundledI2pRouter(context, torSocksPort);
		return new I2pStreamingTransport(router, ioExecutor, handler);
	}
}

package org.zerionproject.transport.i2p;

import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.zerionproject.transport.ZtpConnectionHandler;
import org.zerionproject.transport.ZtpPoller;
import org.zerionproject.transport.ZtpPollerFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Creates the I2P transport's plugin. Registering this factory registers the
 * I2P transport with the key manager and lets the plugin manager start and stop
 * the transport. The connection handler and poller factory are injected as
 * {@link Provider}s so the plugin config stays constructible before the contact
 * and key managers they depend on, matching the Tor factory.
 */
@Immutable
@NotNullByDefault
public class I2pDuplexPluginFactory implements DuplexPluginFactory {

	private final Executor ioExecutor;
	private final Provider<ZtpConnectionHandler> handler;
	private final Provider<ZtpPollerFactory> pollerFactory;
	private final I2pStack stack;

	@Inject
	public I2pDuplexPluginFactory(@IoExecutor Executor ioExecutor,
			Provider<ZtpConnectionHandler> handler,
			Provider<ZtpPollerFactory> pollerFactory, I2pStack stack) {
		this.ioExecutor = ioExecutor;
		this.handler = handler;
		this.pollerFactory = pollerFactory;
		this.stack = stack;
	}

	@Override
	public TransportId getId() {
		return I2pConstants.ID;
	}

	@Override
	public long getMaxLatency() {
		return I2pDuplexPlugin.MAX_LATENCY;
	}

	@Override
	public DuplexPlugin createPlugin(PluginCallback callback) {
		I2pOverlayTransport transport =
				stack.createTransport(ioExecutor, handler.get());
		ZtpPoller poller = pollerFactory.get().create(transport);
		return new I2pDuplexPlugin(transport, poller, callback);
	}
}

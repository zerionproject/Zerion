package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;
import org.briarproject.onionwrapper.TorWrapper;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.zerionproject.core.api.system.WakefulIoExecutor;
import org.zerionproject.core.plugin.tor.TorRendezvousCryptoImpl;

import java.util.concurrent.Executor;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.net.SocketFactory;

/**
 * Creates the native transport's plugin facade. Registering this factory in the
 * plugin config does three things: the key manager registers the transport (the
 * pairing handshake's stream layer needs its id and max latency), the plugin
 * manager starts and stops the Tor lifecycle through the plugin, and the
 * rendezvous poller can create pairing endpoints on it.
 *
 * <p>The transport and poller are injected as {@link Provider}s: the plugin
 * config must be constructible before the contact and key managers it feeds,
 * while the transport stack depends on those managers. Deferring the lookup to
 * {@link #createPlugin} (which the plugin manager calls at startup, after the
 * graph is built) breaks that cycle.
 */
@Immutable
@NotNullByDefault
public class ZtpDuplexPluginFactory implements DuplexPluginFactory {

	private final Executor ioExecutor;
	private final Executor wakefulIoExecutor;
	private final SocketFactory socketFactory;
	private final TorWrapper tor;
	private final Provider<ZtpTorTransport> transport;
	private final Provider<ZtpPollerFactory> pollerFactory;
	private final CryptoComponent crypto;

	@Inject
	public ZtpDuplexPluginFactory(@IoExecutor Executor ioExecutor,
			@WakefulIoExecutor Executor wakefulIoExecutor,
			SocketFactory socketFactory, TorWrapper tor,
			Provider<ZtpTorTransport> transport,
			Provider<ZtpPollerFactory> pollerFactory, CryptoComponent crypto) {
		this.ioExecutor = ioExecutor;
		this.wakefulIoExecutor = wakefulIoExecutor;
		this.socketFactory = socketFactory;
		this.tor = tor;
		this.transport = transport;
		this.pollerFactory = pollerFactory;
		this.crypto = crypto;
	}

	@Override
	public TransportId getId() {
		return TorConstants.ID;
	}

	@Override
	public long getMaxLatency() {
		return ZtpDuplexPlugin.MAX_LATENCY;
	}

	@Override
	public DuplexPlugin createPlugin(PluginCallback callback) {
		ZtpTorTransport torTransport = transport.get();
		ZtpPoller poller = pollerFactory.get().create(torTransport);
		return new ZtpDuplexPlugin(ioExecutor, wakefulIoExecutor, socketFactory,
				tor, torTransport, poller,
				new TorRendezvousCryptoImpl(crypto), callback);
	}
}

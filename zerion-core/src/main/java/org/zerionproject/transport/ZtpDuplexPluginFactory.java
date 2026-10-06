package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.tor.TorWrapper;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.zerionproject.core.api.system.WakefulIoExecutor;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.plugin.tor.TorRendezvousCryptoImpl;

import java.util.concurrent.Executor;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.net.SocketFactory;

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
	private final Provider<B4OnionRotation> b4OnionRotation;
	private final EventBus eventBus;
	private final TorOnionServiceControl onionServiceControl;
	private final org.zerionproject.core.plugin.tor.auth
			.OnionClientAuthManagerImpl onionClientAuth;

	@Inject
	public ZtpDuplexPluginFactory(@IoExecutor Executor ioExecutor,
			@WakefulIoExecutor Executor wakefulIoExecutor,
			SocketFactory socketFactory, TorWrapper tor,
			Provider<ZtpTorTransport> transport,
			Provider<ZtpPollerFactory> pollerFactory, CryptoComponent crypto,
			Provider<B4OnionRotation> b4OnionRotation, EventBus eventBus,
			TorOnionServiceControl onionServiceControl,
			org.zerionproject.core.plugin.tor.auth.OnionClientAuthManagerImpl
					onionClientAuth) {
		this.onionServiceControl = onionServiceControl;
		this.onionClientAuth = onionClientAuth;
		this.ioExecutor = ioExecutor;
		this.wakefulIoExecutor = wakefulIoExecutor;
		this.socketFactory = socketFactory;
		this.tor = tor;
		this.transport = transport;
		this.pollerFactory = pollerFactory;
		this.crypto = crypto;
		this.b4OnionRotation = b4OnionRotation;
		this.eventBus = eventBus;
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
				new TorRendezvousCryptoImpl(crypto), callback,
				b4OnionRotation.get(), eventBus, onionServiceControl,
				onionClientAuth);
	}
}

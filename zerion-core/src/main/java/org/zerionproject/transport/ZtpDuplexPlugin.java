package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;
import org.briarproject.onionwrapper.TorWrapper;
import org.briarproject.onionwrapper.TorWrapper.HiddenServiceProperties;
import org.briarproject.onionwrapper.TorWrapper.Observer;
import org.briarproject.onionwrapper.TorWrapper.TorState;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.keyagreement.KeyAgreementListener;
import org.zerionproject.core.api.plugin.ConnectionHandler;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.plugin.event.TorBootstrapEvent;
import org.zerionproject.core.api.plugin.event.TorOnionPublishedEvent;
import org.zerionproject.core.api.plugin.PluginException;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.AbstractDuplexTransportConnection;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.zerionproject.core.api.rendezvous.RendezvousEndpoint;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.plugin.tor.ChannelOnionAdapter;
import org.zerionproject.core.plugin.tor.TorRendezvousCrypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static org.zerionproject.core.api.plugin.Plugin.State.ACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.ENABLING;
import static org.zerionproject.core.api.plugin.Plugin.State.INACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.STARTING_STOPPING;
import static org.zerionproject.core.api.plugin.TorConstants.HS_PRIVATE_KEY_V3;
import static org.zerionproject.core.api.plugin.TorConstants.PROP_ONION_V3;
import static org.zerionproject.core.plugin.tor.TorRendezvousCrypto.SEED_BYTES;
import static org.zerionproject.core.util.IoUtils.tryToClose;

/**
 * The native transport exposed as a {@link DuplexPlugin}, and the single owner
 * of the Tor lifecycle. Ongoing contact traffic never touches the plugin
 * surface: {@link ZtpTorTransport} accepts and dials contact onions and hands
 * every socket to the native connection handler, and {@link ZtpPoller} decides
 * when to dial. The plugin surface exists for the flows that still run through
 * the app's managers: first-contact pairing (rendezvous endpoints and the
 * dial-side {@link #poll}), voice-call endpoints, and channel onion publishing.
 *
 * <p>Registering this plugin also registers the transport with the key manager
 * (via the factory's id and latency), which the pairing handshake's
 * transport-key stream layer requires.
 *
 * <p>The generic poller must stay idle for this transport ({@link #shouldPoll}
 * is false), so generic sync never dials a contact; only the rendezvous poller
 * and the voice-call manager use the plugin's outgoing connections.
 */
@NotNullByDefault
class ZtpDuplexPlugin implements DuplexPlugin, ChannelOnionAdapter {

	static final int MAX_LATENCY = 30 * 1000;
	static final int MAX_IDLE_TIME = 30 * 1000;
	private static final int SOCKET_TIMEOUT_MS = 60 * 1000;
	private static final int REMOTE_ONION_PORT = 80;
	private static final Pattern ONION_V3 = Pattern.compile("[a-z2-7]{56}");

	private final Executor ioExecutor;
	private final Executor wakefulIoExecutor;
	private final SocketFactory socketFactory;
	private final TorWrapper tor;
	private final ZtpTorTransport transport;
	private final ZtpPoller poller;
	private final TorRendezvousCrypto torRendezvousCrypto;
	private final PluginCallback callback;
	private final B4OnionRotation b4OnionRotation;
	private final EventBus eventBus;
	private final TorOnionServiceControl onionServiceControl;
	private final org.zerionproject.core.plugin.tor.auth
			.OnionClientAuthManagerImpl onionClientAuth;
	private final AtomicBoolean used = new AtomicBoolean(false);

	@Nullable
	private volatile State lastReportedState = null;

	ZtpDuplexPlugin(Executor ioExecutor, Executor wakefulIoExecutor,
			SocketFactory socketFactory, TorWrapper tor,
			ZtpTorTransport transport, ZtpPoller poller,
			TorRendezvousCrypto torRendezvousCrypto, PluginCallback callback,
			B4OnionRotation b4OnionRotation, EventBus eventBus,
			TorOnionServiceControl onionServiceControl,
			org.zerionproject.core.plugin.tor.auth.OnionClientAuthManagerImpl
					onionClientAuth) {
		this.eventBus = eventBus;
		this.onionServiceControl = onionServiceControl;
		this.onionClientAuth = onionClientAuth;
		this.ioExecutor = ioExecutor;
		this.wakefulIoExecutor = wakefulIoExecutor;
		this.socketFactory = socketFactory;
		this.tor = tor;
		this.transport = transport;
		this.poller = poller;
		this.torRendezvousCrypto = torRendezvousCrypto;
		this.callback = callback;
		this.b4OnionRotation = b4OnionRotation;
		tor.setObserver(new Observer() {

			@Override
			public void onState(TorState torState) {
				transport.onTorState(torState);
				State s = mapState(torState);
				if (s != lastReportedState) {
					lastReportedState = s;
					callback.pluginStateChanged(s);
					if (s == ACTIVE) poller.pollNow();
				}
			}

			@Override
			public void onBootstrapPercentage(int percentage) {
				eventBus.broadcast(new TorBootstrapEvent(percentage));
			}

			@Override
			public void onHsDescriptorUpload(String onion) {
				eventBus.broadcast(new TorOnionPublishedEvent(onion));
			}

			@Override
			public void onClockSkewDetected(long skewSeconds) {
			}
		});
	}

	private static State mapState(TorState torState) {
		if (torState == TorState.NOT_STARTED ||
				torState == TorState.STARTING ||
				torState == TorState.STARTED ||
				torState == TorState.STOPPING ||
				torState == TorState.STOPPED) {
			return STARTING_STOPPING;
		}
		if (torState == TorState.CONNECTING) return ENABLING;
		if (torState == TorState.CONNECTED) return ACTIVE;
		return INACTIVE;
	}

	@Override
	public TransportId getId() {
		return TorConstants.ID;
	}

	@Override
	public long getMaxLatency() {
		return MAX_LATENCY;
	}

	@Override
	public int getMaxIdleTime() {
		return MAX_IDLE_TIME;
	}

	@Override
	public void start() throws PluginException {
		if (used.getAndSet(true)) throw new IllegalStateException();
		Settings settings = callback.getSettings();
		@Nullable String privateKey = settings.get(HS_PRIVATE_KEY_V3);
		HiddenServiceProperties hs;
		try {
			hs = transport.start(privateKey);
		} catch (IOException e) {
			throw new PluginException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PluginException();
		}
		if (privateKey == null) {
			Settings updated = new Settings();
			updated.put(HS_PRIVATE_KEY_V3, hs.privKey);
			callback.mergeSettings(updated);
		}
		TransportProperties props = new TransportProperties();
		props.put(PROP_ONION_V3, hs.onion);
		props.put(org.zerionproject.core.api.plugin.TorConstants
				.PROP_ONION_AUTH_SUPPORTED, String.valueOf(
				org.zerionproject.core.plugin.tor.auth.OnionAuthRecords
						.PROTOCOL_VERSION));
		callback.mergeLocalProperties(props);
		transport.setDialListener((contactId, onion) -> onionClientAuth
				.dialSucceeded(new ContactId(contactId), onion));
		onionClientAuth.attachTor(onionServiceControl,
				transport.getAuthorizedLocalPort(),
				c -> poller.dialNow(c.getInt()));
		transport.setTorRestartedListener(() -> onionClientAuth.attachTor(
				onionServiceControl, transport.getAuthorizedLocalPort(),
				c -> poller.dialNow(c.getInt())));
		b4OnionRotation.bindAdapter(new B4OnionRotation.B4TorAdapter() {
			@Override
			public HiddenServiceProperties publishHiddenService(
					@Nullable String privKey) throws IOException {
				return transport.publishHiddenService(transport.getLocalPort(),
						REMOTE_ONION_PORT, privKey);
			}

			@Override
			public boolean isPublished(String onion) {
				return transport.publishedOnions().contains(onion);
			}

			@Override
			public void removeHiddenService(String onion) throws IOException {
				transport.removeHiddenService(onion);
			}

			@Override
			public void updateTorCurrentPrivKey(String newPrivKey) {
				Settings updated = new Settings();
				updated.put(HS_PRIVATE_KEY_V3, newPrivKey);
				callback.mergeSettings(updated);
			}

			@Override
			public void mergeTorLocalProperties(TransportProperties p) {
				callback.mergeLocalProperties(p);
			}
		});
		try {
			b4OnionRotation.republishPendingOnion();
		} catch (DbException e) {
			throw new PluginException(e);
		}
		b4OnionRotation.startPeriodicEvaluation();
		poller.start();
	}

	@Override
	public void stop() throws PluginException {
		onionClientAuth.detachTor();
		transport.setTorRestartedListener(null);
		transport.setDialListener(null);
		b4OnionRotation.shutdown();
		poller.stop();
		try {
			transport.stop();
		} catch (IOException e) {
			throw new PluginException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PluginException();
		}
	}

	@Override
	public State getState() {
		return mapState(tor.getTorState());
	}

	@Override
	public int getReasonsDisabled() {
		return 0;
	}

	@Override
	public boolean shouldPoll() {
		return false;
	}

	@Override
	public int getPollingInterval() {
		return MAX_LATENCY;
	}

	@Override
	public void poll(Collection<Pair<TransportProperties, ConnectionHandler>>
			properties) {
		if (getState() != ACTIVE) return;
		for (Pair<TransportProperties, ConnectionHandler> p : properties) {
			wakefulIoExecutor.execute(() -> {
				DuplexTransportConnection d = createConnection(p.getFirst());
				if (d != null) p.getSecond().handleConnection(d);
			});
		}
	}

	@Override
	@Nullable
	public DuplexTransportConnection createConnection(TransportProperties p) {
		if (getState() != ACTIVE) return null;
		String onion = p.get(PROP_ONION_V3);
		if (onion == null || !ONION_V3.matcher(onion).matches()) return null;
		try {
			Socket s = socketFactory.createSocket(onion + ".onion",
					REMOTE_ONION_PORT);
			configureSocket(s);
			return new ZtpTransportConnection(this, s);
		} catch (IOException e) {
			return null;
		}
	}

	@Override
	public boolean supportsKeyAgreement() {
		return false;
	}

	@Override
	public KeyAgreementListener createKeyAgreementListener(
			byte[] localCommitment) {
		throw new UnsupportedOperationException();
	}

	@Override
	public DuplexTransportConnection createKeyAgreementConnection(
			byte[] remoteCommitment, BdfList descriptor) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean supportsRendezvous() {
		return true;
	}

	@Override
	@Nullable
	public RendezvousEndpoint createRendezvousEndpoint(KeyMaterialSource k,
			boolean alice, ConnectionHandler incoming) {
		byte[] aliceSeed = k.getKeyMaterial(SEED_BYTES);
		byte[] bobSeed = k.getKeyMaterial(SEED_BYTES);
		byte[] localSeed = alice ? aliceSeed : bobSeed;
		byte[] remoteSeed = alice ? bobSeed : aliceSeed;
		String blob = torRendezvousCrypto.getPrivateKeyBlob(localSeed);
		String localOnion = torRendezvousCrypto.getOnion(localSeed);
		String remoteOnion = torRendezvousCrypto.getOnion(remoteSeed);
		TransportProperties remoteProperties = new TransportProperties();
		remoteProperties.put(PROP_ONION_V3, remoteOnion);
		try {
			@SuppressWarnings("resource")
			ServerSocket ss = new ServerSocket();
			ss.bind(new InetSocketAddress("127.0.0.1", 0));
			int port = ss.getLocalPort();
			try {
				transport.publishHiddenService(port, REMOTE_ONION_PORT, blob);
			} catch (IOException e) {
				tryToClose(ss);
				return null;
			}
			ioExecutor.execute(() -> {
				while (true) {
					Socket s;
					try {
						s = ss.accept();
					} catch (IOException e) {
						return;
					}
					try {
						configureSocket(s);
						incoming.handleConnection(
								new ZtpTransportConnection(this, s));
					} catch (IOException e) {
						tryToClose(s);
					}
				}
			});
			return new RendezvousEndpoint() {

				@Override
				public TransportProperties getRemoteTransportProperties() {
					return remoteProperties;
				}

				@Override
				public void close() throws IOException {
					try {
						transport.removeHiddenService(localOnion);
					} finally {
						tryToClose(ss);
					}
				}
			};
		} catch (IOException e) {
			return null;
		}
	}

	@Override
	public ChannelOnionHandle publishChannelOnion(int localPort,
			@Nullable String privateKey) throws IOException {
		HiddenServiceProperties hs = transport.publishHiddenService(localPort,
				REMOTE_ONION_PORT, privateKey);
		return new ChannelOnionHandle(hs.onion, hs.privKey);
	}

	@Override
	public void removeChannelOnion(String onion) throws IOException {
		transport.removeHiddenService(onion);
	}

	private static void configureSocket(Socket s) throws IOException {
		s.setSoTimeout(SOCKET_TIMEOUT_MS);
		try {
			s.setTcpNoDelay(true);
		} catch (java.net.SocketException ignored) {
		}
	}

	private static class ZtpTransportConnection
			extends AbstractDuplexTransportConnection {

		private final Socket socket;

		private ZtpTransportConnection(Plugin plugin, Socket socket) {
			super(plugin);
			this.socket = socket;
		}

		@Override
		protected InputStream getInputStream() throws IOException {
			return org.zerionproject.core.util.IoUtils.getInputStream(socket);
		}

		@Override
		protected OutputStream getOutputStream() throws IOException {
			return org.zerionproject.core.util.IoUtils.getOutputStream(socket);
		}

		@Override
		protected void closeConnection(boolean exception) throws IOException {
			socket.close();
		}
	}
}

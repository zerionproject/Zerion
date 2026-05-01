package org.briarproject.bramble.plugin.tor;

import org.briarproject.bramble.PoliteExecutor;
import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.battery.BatteryManager;
import org.briarproject.bramble.api.battery.event.BatteryEvent;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.keyagreement.KeyAgreementListener;
import org.briarproject.bramble.api.network.NetworkManager;
import org.briarproject.bramble.api.network.NetworkStatus;
import org.briarproject.bramble.api.network.event.NetworkStatusEvent;
import org.briarproject.bramble.api.plugin.Backoff;
import org.briarproject.bramble.api.plugin.ConnectionHandler;
import org.briarproject.bramble.api.plugin.PluginCallback;
import org.briarproject.bramble.api.plugin.PluginException;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.rendezvous.KeyMaterialSource;
import org.briarproject.bramble.api.rendezvous.RendezvousEndpoint;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.event.SettingsUpdatedEvent;
import org.briarproject.nullsafety.InterfaceNotNullByDefault;
import org.briarproject.nullsafety.NotNullByDefault;
import org.briarproject.onionwrapper.CircumventionProvider;
import org.briarproject.onionwrapper.CircumventionProvider.BridgeType;
import org.briarproject.onionwrapper.LocationUtils;
import org.briarproject.onionwrapper.TorWrapper;
import org.briarproject.onionwrapper.TorWrapper.HiddenServiceProperties;
import org.briarproject.onionwrapper.TorWrapper.Observer;
import org.briarproject.onionwrapper.TorWrapper.TorState;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.net.SocketFactory;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.api.plugin.Plugin.State.DISABLED;
import static org.briarproject.bramble.api.plugin.Plugin.State.ENABLING;
import static org.briarproject.bramble.api.plugin.Plugin.State.INACTIVE;
import static org.briarproject.bramble.api.plugin.Plugin.State.STARTING_STOPPING;
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_PREF_PLUGIN_ENABLE;
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_PREF_TOR_MOBILE;
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_PREF_TOR_NETWORK;
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_PREF_TOR_ONLY_WHEN_CHARGING;
import static org.briarproject.bramble.api.plugin.TorConstants.HS_PRIVATE_KEY_V3;
import static org.briarproject.bramble.api.plugin.TorConstants.ID;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_MOBILE;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_NETWORK;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_NETWORK_AUTOMATIC;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_NETWORK_WITH_BRIDGES;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_ONLY_WHEN_CHARGING;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_PORT;
import static org.briarproject.bramble.api.plugin.TorConstants.PROP_ONION_V3;
import static org.briarproject.bramble.api.plugin.TorConstants.REASON_BATTERY;
import static org.briarproject.bramble.api.plugin.TorConstants.REASON_MOBILE_DATA;
import static org.briarproject.bramble.plugin.tor.TorRendezvousCrypto.SEED_BYTES;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.MEEK;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.SNOWFLAKE;

@InterfaceNotNullByDefault
class TorPlugin implements DuplexPlugin, EventListener,
		B4OnionRotation.B4TorAdapter {
	private static final Pattern ONION_V3 = Pattern.compile("[a-z2-7]{56}");

	protected final Executor ioExecutor;
	private final Executor wakefulIoExecutor;
	private final Executor connectionStatusExecutor;
	private final NetworkManager networkManager;
	private final LocationUtils locationUtils;
	private final SocketFactory torSocketFactory;
	private final CircumventionProvider circumventionProvider;
	private final BatteryManager batteryManager;
	private final Backoff backoff;
	private final TorRendezvousCrypto torRendezvousCrypto;
	private final TorWrapper tor;
	private final PluginCallback callback;
	private final long maxLatency;
	private final int maxIdleTime;
	private final int socketTimeout;
	private final AtomicBoolean used = new AtomicBoolean(false);
	private final B4OnionRotation b4OnionRotation;

	protected final PluginState state = new PluginState();

	private volatile Settings settings = null;
	private volatile State lastReportedState = null;

	private final java.util.Map<String, ServerSocket>
			b4OnionToServerSocket =
			new java.util.concurrent.ConcurrentHashMap<>();

	private final java.util.Queue<Long> recentB4Accepts =
			new java.util.concurrent.ConcurrentLinkedQueue<>();

	TorPlugin(Executor ioExecutor,
			Executor wakefulIoExecutor,
			NetworkManager networkManager,
			LocationUtils locationUtils,
			SocketFactory torSocketFactory,
			CircumventionProvider circumventionProvider,
			BatteryManager batteryManager,
			Backoff backoff,
			TorRendezvousCrypto torRendezvousCrypto,
			TorWrapper tor,
			PluginCallback callback,
			long maxLatency,
			int maxIdleTime,
			B4OnionRotation b4OnionRotation) {
		this.ioExecutor = ioExecutor;
		this.wakefulIoExecutor = wakefulIoExecutor;
		this.networkManager = networkManager;
		this.locationUtils = locationUtils;
		this.torSocketFactory = torSocketFactory;
		this.circumventionProvider = circumventionProvider;
		this.batteryManager = batteryManager;
		this.backoff = backoff;
		this.torRendezvousCrypto = torRendezvousCrypto;
		this.tor = tor;
		this.callback = callback;
		this.maxLatency = maxLatency;
		this.maxIdleTime = maxIdleTime;
		this.b4OnionRotation = b4OnionRotation;
		if (maxIdleTime > Integer.MAX_VALUE / 2) {
			socketTimeout = Integer.MAX_VALUE;
		} else {
			socketTimeout = maxIdleTime * 2;
		}
		connectionStatusExecutor =
				new PoliteExecutor("TorPlugin", ioExecutor, 1);
		tor.setObserver(new Observer() {

			@Override
			public void onState(TorState torState) {
				State s = state.getState(torState);
				if (s == ACTIVE) backoff.reset();
				if (s != lastReportedState) {
					lastReportedState = s;
					callback.pluginStateChanged(s);
				}
			}

			@Override
			public void onBootstrapPercentage(int percentage) {
			}

			@Override
			public void onHsDescriptorUpload(String onion) {
			}

			@Override
			public void onClockSkewDetected(long skewSeconds) {
			}
		});
	}

	@Override
	public TransportId getId() {
		return TorConstants.ID;
	}

	@Override
	public long getMaxLatency() {
		return maxLatency;
	}

	@Override
	public int getMaxIdleTime() {
		return maxIdleTime;
	}

	@Override
	public void start() throws PluginException {
		if (used.getAndSet(true)) throw new IllegalStateException();
		settings = callback.getSettings();
		try {
			tor.start();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PluginException();
		} catch (IOException e) {
			throw new PluginException(e);
		}
		updateConnectionStatus(networkManager.getNetworkStatus(),
				batteryManager.isCharging());
		bind();
	}

	private void bind() {
		ioExecutor.execute(() -> {
			String portString = settings.get(PREF_TOR_PORT);
			int port;
			if (isNullOrEmpty(portString)) port = 0;
			else port = Integer.parseInt(portString);
			ServerSocket ss = null;
			try {
				ss = new ServerSocket();
				ss.bind(new InetSocketAddress("127.0.0.1", port));
			} catch (IOException e) {
				tryToClose(ss);
				return;
			}
			if (!state.setServerSocket(ss)) {
				tryToClose(ss);
				return;
			}
			int localPort = ss.getLocalPort();
			Settings s = new Settings();
			s.put(PREF_TOR_PORT, String.valueOf(localPort));
			callback.mergeSettings(s);
			ioExecutor.execute(() -> publishHiddenService(localPort));
			backoff.reset();
			acceptContactConnections(ss);
		});
	}

	private void publishHiddenService(int localPort) {
		if (!tor.isTorRunning()) return;
		String privKey = settings.get(HS_PRIVATE_KEY_V3);
		HiddenServiceProperties hsProps;
		try {
			hsProps = tor.publishHiddenService(localPort, 80, privKey);
		} catch (IOException e) {
			return;
		}
		if (privKey == null) {
			TransportProperties p = new TransportProperties();
			p.put(PROP_ONION_V3, hsProps.onion);
			callback.mergeLocalProperties(p);
			Settings s = new Settings();
			s.put(HS_PRIVATE_KEY_V3, hsProps.privKey);
			callback.mergeSettings(s);
		}
		b4OnionRotation.bindAdapter(this);
		if (org.briarproject.bramble.api.plugin.B4Constants.B4_DEBUG_LOG) {
			java.util.logging.Logger.getLogger(TorPlugin.class.getName())
					.info(String.format(
							"[B4] TorPlugin: B.4 adapter bound, "
									+ "localPort=%d",
							localPort));
		}
		try {
			b4OnionRotation.resumeIfPromotionInterrupted();
		} catch (org.briarproject.bramble.api.db.DbException e) {
		}
	}

	@Override
	public HiddenServiceProperties publishHiddenService(@Nullable String privKey)
			throws IOException {
		ServerSocket b4Ss = new ServerSocket();
		try {
			b4Ss.bind(new InetSocketAddress("127.0.0.1", 0));
		} catch (IOException e) {
			tryToClose(b4Ss);
			throw e;
		}
		int b4Port = b4Ss.getLocalPort();
		HiddenServiceProperties hsProps;
		try {
			hsProps = tor.publishHiddenService(b4Port, 80, privKey);
		} catch (IOException e) {
			tryToClose(b4Ss);
			throw e;
		}
		b4OnionToServerSocket.put(hsProps.onion, b4Ss);
		ioExecutor.execute(() -> acceptB4NewOnionConnections(b4Ss));
		if (org.briarproject.bramble.api.plugin.B4Constants.B4_DEBUG_LOG) {
			java.util.logging.Logger.getLogger(TorPlugin.class.getName())
					.info(String.format(
							"[B4] TorPlugin: bound new onion=%s.onion on "
									+ "localPort=%d (separate ServerSocket "
									+ "for migration detection)",
							hsProps.onion, b4Port));
		}
		return hsProps;
	}

	@Override
	public void removeHiddenService(String onion) throws IOException {
		tor.removeHiddenService(onion);
		ServerSocket ss = b4OnionToServerSocket.remove(onion);
		if (ss != null) tryToClose(ss);
		if (org.briarproject.bramble.api.plugin.B4Constants.B4_DEBUG_LOG) {
			java.util.logging.Logger.getLogger(TorPlugin.class.getName())
					.info(String.format(
							"[B4] TorPlugin: removed onion=%s.onion + closed "
									+ "its ServerSocket",
							onion));
		}
	}

	private void acceptB4NewOnionConnections(ServerSocket ss) {
		while (true) {
			Socket s;
			try {
				s = ss.accept();
				s.setSoTimeout(socketTimeout);
			} catch (IOException e) {
				return;
			}
			recentB4Accepts.add(System.currentTimeMillis());
			if (org.briarproject.bramble.api.plugin.B4Constants.B4_DEBUG_LOG) {
				java.util.logging.Logger.getLogger(TorPlugin.class.getName())
						.info("[B4] TorPlugin: accepted inbound on new-"
								+ "onion port (awaiting ContactConnectedEvent "
								+ "correlation)");
			}
			callback.handleConnection(new TorTransportConnection(this, s));
		}
	}

	@Override
	public void updateTorCurrentPrivKey(String newPrivKey) {
		Settings s = new Settings();
		s.put(HS_PRIVATE_KEY_V3, newPrivKey);
		callback.mergeSettings(s);
	}

	@Override
	public void mergeTorLocalProperties(TransportProperties props) {
		callback.mergeLocalProperties(props);
	}

	private void acceptContactConnections(ServerSocket ss) {
		while (true) {
			Socket s;
			try {
				s = ss.accept();
				s.setSoTimeout(socketTimeout);
			} catch (IOException e) {
				state.clearServerSocket(ss);
				return;
			}
			backoff.reset();
			callback.handleConnection(new TorTransportConnection(this, s));
		}
	}

	private void enableBridges(List<BridgeType> bridgeTypes, String countryCode)
			throws IOException {
		if (bridgeTypes.isEmpty()) {
			tor.disableBridges();
		} else {
			List<String> bridges = new ArrayList<>();
			for (BridgeType bridgeType : bridgeTypes) {
				bridges.addAll(circumventionProvider.getBridges(bridgeType,
						countryCode));
			}
			tor.enableBridges(bridges);
		}
	}

	@Override
	public void stop() {
		ServerSocket ss = state.setStopped();
		tryToClose(ss);
		for (ServerSocket b4Ss : b4OnionToServerSocket.values()) {
			tryToClose(b4Ss);
		}
		b4OnionToServerSocket.clear();
		recentB4Accepts.clear();
		try {
			tor.stop();
		} catch (IOException e) {
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public State getState() {
		return state.getState();
	}

	@Override
	public int getReasonsDisabled() {
		return state.getReasonsDisabled();
	}

	@Override
	public boolean shouldPoll() {
		return true;
	}

	@Override
	public int getPollingInterval() {
		return backoff.getPollingInterval();
	}

	@Override
	public void poll(Collection<Pair<TransportProperties, ConnectionHandler>>
			properties) {
		if (getState() != ACTIVE) return;
		backoff.increment();
		for (Pair<TransportProperties, ConnectionHandler> p : properties) {
			connect(p.getFirst(), p.getSecond());
		}
	}

	private void connect(TransportProperties p, ConnectionHandler h) {
		wakefulIoExecutor.execute(() -> {
			DuplexTransportConnection d = createConnection(p);
			if (d != null) {
				backoff.reset();
				h.handleConnection(d);
			}
		});
	}

	@Override
	public DuplexTransportConnection createConnection(TransportProperties p) {
		if (getState() != ACTIVE) return null;
		String onion3 = p.get(PROP_ONION_V3);
		if (onion3 != null && !ONION_V3.matcher(onion3).matches()) {
			onion3 = null;
		}
		String fallback = p.get(
				org.briarproject.bramble.api.plugin.B4Constants
						.B4_LOCAL_FALLBACK_ONION_KEY);
		if (fallback != null && !ONION_V3.matcher(fallback).matches()) {
			fallback = null;
		}
		String contactIdStr = p.get(
				org.briarproject.bramble.api.plugin.B4Constants
						.B4_LOCAL_CONTACT_ID_KEY);
		org.briarproject.bramble.api.contact.ContactId b4Cid = null;
		if (contactIdStr != null && !contactIdStr.isEmpty()) {
			try {
				b4Cid = new org.briarproject.bramble.api.contact.ContactId(
						Integer.parseInt(contactIdStr));
			} catch (NumberFormatException ignored) {
			}
		}
		if (onion3 == null && fallback == null) return null;

		DuplexTransportConnection conn = dialOnion(onion3);
		if (conn != null) {
			if (b4Cid != null && onion3 != null
					&& !onion3.equals(fallback)) {
				try {
					b4OnionRotation.onSuccessfulConnect(b4Cid, onion3);
				} catch (org.briarproject.bramble.api.db.DbException e) {
					if (org.briarproject.bramble.api.plugin.B4Constants
							.B4_DEBUG_LOG) {
						java.util.logging.Logger
								.getLogger(TorPlugin.class.getName())
								.warning(
										"[B4] TorPlugin: onSuccessfulConnect "
												+ "failed: " + e.getMessage());
					}
				}
			}
			return conn;
		}
		if (fallback != null && !fallback.equals(onion3)) {
			if (b4Cid != null && onion3 != null) {
				try {
					b4OnionRotation.onPendingDialFailed(b4Cid);
				} catch (org.briarproject.bramble.api.db.DbException e) {
					if (org.briarproject.bramble.api.plugin.B4Constants
							.B4_DEBUG_LOG) {
						java.util.logging.Logger
								.getLogger(TorPlugin.class.getName())
								.warning(
										"[B4] TorPlugin: onPendingDialFailed "
												+ "failed: " + e.getMessage());
					}
				}
			}
			if (org.briarproject.bramble.api.plugin.B4Constants.B4_DEBUG_LOG) {
				java.util.logging.Logger.getLogger(TorPlugin.class.getName())
						.info("[B4] TorPlugin: pending onion dial failed, "
								+ "falling back to previous onion");
			}
			return dialOnion(fallback);
		}
		return null;
	}

	@Nullable
	private DuplexTransportConnection dialOnion(@Nullable String onion) {
		if (onion == null) return null;
		Socket s = null;
		try {
			s = torSocketFactory.createSocket(onion + ".onion", 80);
			s.setSoTimeout(socketTimeout);
			return new TorTransportConnection(this, s);
		} catch (IOException e) {
			tryToClose(s);
			return null;
		}
	}

	@Override
	public boolean supportsKeyAgreement() {
		return false;
	}

	@Override
	public KeyAgreementListener createKeyAgreementListener(byte[] commitment) {
		throw new UnsupportedOperationException();
	}

	@Override
	public DuplexTransportConnection createKeyAgreementConnection(
			byte[] commitment, BdfList descriptor) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean supportsRendezvous() {
		return true;
	}

	@Override
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
			ioExecutor.execute(() -> {
				try {
					while (true) {
						Socket s = ss.accept();
						incoming.handleConnection(
								new TorTransportConnection(this, s));
					}
				} catch (IOException e) {
				}
			});
			tor.publishHiddenService(port, 80, blob);
			return new RendezvousEndpoint() {

				@Override
				public TransportProperties getRemoteTransportProperties() {
					return remoteProperties;
				}

				@Override
				public void close() throws IOException {
					try {
						tor.removeHiddenService(localOnion);
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
	public void eventOccurred(Event e) {
		if (e instanceof SettingsUpdatedEvent) {
			SettingsUpdatedEvent s = (SettingsUpdatedEvent) e;
			if (s.getNamespace().equals(ID.getString())) {
				settings = s.getSettings();
				updateConnectionStatus(networkManager.getNetworkStatus(),
						batteryManager.isCharging());
			}
		} else if (e instanceof NetworkStatusEvent) {
			updateConnectionStatus(((NetworkStatusEvent) e).getStatus(),
					batteryManager.isCharging());
		} else if (e instanceof BatteryEvent) {
			updateConnectionStatus(networkManager.getNetworkStatus(),
					((BatteryEvent) e).isCharging());
		} else if (e instanceof
				org.briarproject.bramble.api.plugin.event.ContactConnectedEvent) {
			final org.briarproject.bramble.api.contact.ContactId cid =
					((org.briarproject.bramble.api.plugin.event
							.ContactConnectedEvent) e).getContactId();
			final boolean migrated;
			{
				long now = System.currentTimeMillis();
				long window = org.briarproject.bramble.api.plugin
						.B4Constants.B4_ACCEPT_CORRELATION_WINDOW_MS;
				Long acceptTime = recentB4Accepts.poll();
				while (acceptTime != null && now - acceptTime > window) {
					acceptTime = recentB4Accepts.poll();
				}
				migrated = acceptTime != null;
			}
			ioExecutor.execute(() -> {
				try {
					b4OnionRotation.evaluateTrigger();
					b4OnionRotation.onPeerSyncSessionEstablished(cid);
					if (migrated) {
						b4OnionRotation.onInboundConnectionOnNewOnion(cid);
					}
				} catch (org.briarproject.bramble.api.db.DbException dbEx) {
				}
			});
		}
	}

	private void updateConnectionStatus(NetworkStatus status,
			boolean charging) {
		connectionStatusExecutor.execute(() -> {
			if (!tor.isTorRunning()) return;
			boolean online = status.isConnected();
			boolean wifi = status.isWifi();
			boolean ipv6Only = status.isIpv6Only();
			String country = locationUtils.getCurrentCountry();
			boolean bridgesByDefault =
					circumventionProvider.shouldUseBridges(country);
			boolean enabledByUser = settings.getBoolean(PREF_PLUGIN_ENABLE,
					DEFAULT_PREF_PLUGIN_ENABLE);
			int network = settings.getInt(PREF_TOR_NETWORK,
					DEFAULT_PREF_TOR_NETWORK);
			boolean useMobile = settings.getBoolean(PREF_TOR_MOBILE,
					DEFAULT_PREF_TOR_MOBILE);
			boolean onlyWhenCharging =
					settings.getBoolean(PREF_TOR_ONLY_WHEN_CHARGING,
							DEFAULT_PREF_TOR_ONLY_WHEN_CHARGING);
			boolean automatic = network == PREF_TOR_NETWORK_AUTOMATIC;

			int reasonsDisabled = 0;
			boolean enableNetwork = false, enableConnectionPadding = false;
			List<BridgeType> bridgeTypes = emptyList();

			if (online) {
				if (!enabledByUser) {
					reasonsDisabled |= REASON_USER;
				}
				if (!charging && onlyWhenCharging) {
					reasonsDisabled |= REASON_BATTERY;
				}
				if (!useMobile && !wifi) {
					reasonsDisabled |= REASON_MOBILE_DATA;
				}

				if (reasonsDisabled == 0) {
					enableNetwork = true;
					if (network == PREF_TOR_NETWORK_WITH_BRIDGES ||
							(automatic && bridgesByDefault)) {
						if (ipv6Only) {
							bridgeTypes = asList(MEEK, SNOWFLAKE);
						} else {
							bridgeTypes = circumventionProvider
									.getSuitableBridgeTypes(country);
						}
					}
					if (wifi && charging) {
						enableConnectionPadding = true;
					}
				}
			}

			state.setReasonsDisabled(reasonsDisabled);

			try {
				if (enableNetwork) {
					enableBridges(bridgeTypes, country);
					tor.enableConnectionPadding(enableConnectionPadding);
					tor.enableIpv6(ipv6Only);
				}
				tor.enableNetwork(enableNetwork);
			} catch (IOException e) {
			}
		});
	}

	@ThreadSafe
	@NotNullByDefault
	private class PluginState {

		@GuardedBy("this")
		private boolean settingsChecked = false;

		@GuardedBy("this")
		private int reasonsDisabled = 0;

		@GuardedBy("this")
		@Nullable
		private ServerSocket serverSocket = null;

		@Nullable
		private synchronized ServerSocket setStopped() {
			ServerSocket ss = serverSocket;
			serverSocket = null;
			return ss;
		}

		private synchronized void setReasonsDisabled(int reasons) {
			boolean wasChecked = settingsChecked;
			settingsChecked = true;
			int oldReasons = reasonsDisabled;
			reasonsDisabled = reasons;
			if (!wasChecked || reasons != oldReasons) {
				State s = getState();
				if (s != lastReportedState) {
					lastReportedState = s;
					callback.pluginStateChanged(s);
				}
			}
		}

		private synchronized boolean setServerSocket(ServerSocket ss) {
			if (serverSocket != null || !tor.isTorRunning()) return false;
			serverSocket = ss;
			return true;
		}

		private synchronized void clearServerSocket(ServerSocket ss) {
			if (serverSocket == ss) serverSocket = null;
		}

		private synchronized State getState() {
			return getState(tor.getTorState());
		}

		private synchronized State getState(TorState torState) {
			if (torState == TorState.NOT_STARTED ||
					torState == TorState.STARTING ||
					torState == TorState.STARTED ||
					torState == TorState.STOPPING ||
					torState == TorState.STOPPED ||
					!settingsChecked) {
				return STARTING_STOPPING;
			}
			if (reasonsDisabled != 0) return DISABLED;
			if (torState == TorState.CONNECTING) return ENABLING;
			if (torState == TorState.CONNECTED) return ACTIVE;
			return INACTIVE;
		}

		private synchronized int getReasonsDisabled() {
			return getState() == DISABLED ? reasonsDisabled : 0;
		}
	}
}

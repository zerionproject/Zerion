package org.zerionproject.transport;

import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.tor.CircumventionProvider;
import org.zerionproject.tor.TorWrapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SC-TOR-06: Tor forgets every credential added over its control port
 * whenever its configuration changes, so the transport must announce each
 * reconfiguration of the running process after the change has been made,
 * and only while it is running; whatever the listener does must not
 * disturb the network handling itself.
 */
public class ZtpTorTransportRefeedTest {

	private static class RecordingTor implements TorWrapper {
		final List<String> calls =
				Collections.synchronizedList(new ArrayList<>());
		volatile TorState state = TorState.CONNECTED;
		volatile boolean refuseBridges = false;

		public void start() {
			calls.add("start");
		}

		public void stop() {
			calls.add("stop");
		}

		public void setObserver(@Nullable Observer observer) {
		}

		public TorState getTorState() {
			return state;
		}

		public boolean isTorRunning() {
			return true;
		}

		public HiddenServiceProperties publishHiddenService(int localPort,
				int remotePort, @Nullable String privateKey) {
			calls.add("publish");
			return new HiddenServiceProperties("onion",
					privateKey == null ? "generated" : privateKey);
		}

		public void removeHiddenService(String onion) {
			calls.add("remove");
		}

		public void enableNetwork(boolean enable) {
			calls.add("network:" + enable);
		}

		public void enableBridges(List<String> bridges) throws IOException {
			calls.add("bridges:on");
			if (refuseBridges) throw new IOException("552 rejected");
		}

		public void disableBridges() {
			calls.add("bridges:off");
		}

		public void enableConnectionPadding(boolean enable) {
			calls.add("padding:" + enable);
		}

		public void enableIpv6(boolean ipv6Only) {
		}

		public File getLyrebirdExecutableFile() {
			return new File(".");
		}
	}

	private static class FixedSettings implements SettingsManager {
		private final Settings settings;

		FixedSettings(Settings settings) {
			this.settings = settings;
		}

		public Settings getSettings(String namespace) {
			return settings;
		}

		public Settings getSettings(Transaction txn, String namespace) {
			return settings;
		}

		public void mergeSettings(Settings s, String namespace) {
		}

		public void mergeSettings(Transaction txn, Settings s,
				String namespace) {
		}
	}

	private static class NoEvents implements EventBus {
		public void addListener(EventListener l) {
		}

		public void removeListener(EventListener l) {
		}

		public void broadcast(Event e) {
		}
	}

	private static class NoBridges implements CircumventionProvider {
		public boolean shouldUseBridges(String countryCode) {
			return false;
		}

		public List<BridgeType> getSuitableBridgeTypes(String countryCode) {
			return Collections.emptyList();
		}

		public List<String> getBridges(BridgeType type, String countryCode) {
			return Collections.emptyList();
		}
	}

	private static Settings withBridges() {
		Settings s = new Settings();
		s.putInt(org.zerionproject.core.api.plugin.TorConstants
				.PREF_TOR_NETWORK, org.zerionproject.core.api.plugin
				.TorConstants.PREF_TOR_NETWORK_WITH_BRIDGES);
		s.put(org.zerionproject.core.api.plugin.TorConstants
				.PREF_TOR_CUSTOM_BRIDGES, "obfs4 1.2.3.4:443 ABCDEF");
		return s;
	}

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final AtomicLong now = new AtomicLong(1_000_000L);
	private final RecordingTor tor = new RecordingTor();
	@Nullable
	private ZtpTorTransport transport;

	private final SocketFactory refusingFactory = new SocketFactory() {
		@Override
		public Socket createSocket(String host, int port) throws IOException {
			throw new IOException("refused");
		}

		@Override
		public Socket createSocket(String host, int port,
				InetAddress localHost, int localPort) throws IOException {
			return createSocket(host, port);
		}

		@Override
		public Socket createSocket(InetAddress host, int port)
				throws IOException {
			return createSocket(host.getHostName(), port);
		}

		@Override
		public Socket createSocket(InetAddress address, int port,
				InetAddress localAddress, int localPort) throws IOException {
			return createSocket(address.getHostName(), port);
		}
	};

	private ZtpTorTransport transport(Settings settings) {
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
			@Override
			public void handlePaired(TransportId transportId, int contactId,
					boolean incoming, InputStream in, OutputStream out) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void handleOutgoing(TransportId transportId,
					int contactId, InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId,
					InputStream in, OutputStream out) {
			}
		};
		TorBridgeConfigurator bridges = new TorBridgeConfigurator(
				new FixedSettings(settings), new NoBridges(), () -> "", tor,
				new NoEvents(), exec);
		ZtpTorTransport t = new ZtpTorTransport(tor, refusingFactory,
				refusingFactory, exec, handler, bridges, () -> {
				}, new TorProcessWatch());
		t.clock = now::get;
		t.sleeper = ms -> {
		};
		transport = t;
		return t;
	}

	private ZtpTorTransport started(Settings settings) throws Exception {
		ZtpTorTransport t = transport(settings);
		t.setTorReconfiguredListener(() -> tor.calls.add("refeed"));
		t.start(null);
		tor.calls.clear();
		return t;
	}

	@After
	public void tearDown() throws Exception {
		ZtpTorTransport t = transport;
		if (t != null) {
			try {
				t.stop();
			} catch (IllegalStateException | IOException ignored) {
			}
		}
		exec.shutdownNow();
	}

	@Test(timeout = 15_000)
	public void credentialsAreReinstalledAfterEveryNetworkChange()
			throws Exception {
		ZtpTorTransport t = started(new Settings());
		t.setNetworkEnabled(false);
		assertEquals(Arrays.asList("network:false", "refeed"), tor.calls);
		tor.calls.clear();
		t.setNetworkEnabled(true);
		assertEquals(Arrays.asList("bridges:off", "network:true", "refeed"),
				tor.calls);
	}

	@Test(timeout = 15_000)
	public void reinstallFollowsANetworkRestart() throws Exception {
		ZtpTorTransport t = started(new Settings());
		tor.state = TorWrapper.TorState.CONNECTING;
		t.restartNetwork();
		assertEquals(Arrays.asList("network:false", "bridges:off",
				"network:true", "refeed"), tor.calls);
	}

	@Test(timeout = 15_000)
	public void reinstallFollowsTheBounceOfADegradedNetwork()
			throws Exception {
		ZtpTorTransport t = started(new Settings());
		t.onTorState(TorWrapper.TorState.CONNECTED);
		t.onTorState(TorWrapper.TorState.CONNECTING);
		tor.state = TorWrapper.TorState.CONNECTING;
		now.addAndGet(ZtpTorTransport.DEGRADED_GRACE_MS + 1);
		t.setNetworkEnabled(true);
		assertEquals(Arrays.asList("bridges:off", "network:false",
				"bridges:off", "network:true", "refeed"), tor.calls);
	}

	@Test(timeout = 15_000)
	public void reinstallFollowsARefusedBridgeConfigurationToo()
			throws Exception {
		ZtpTorTransport t = started(withBridges());
		tor.refuseBridges = true;
		t.setNetworkEnabled(true);
		assertEquals(Arrays.asList("bridges:on", "network:false", "refeed"),
				tor.calls);
	}

	@Test(timeout = 15_000)
	public void nothingIsAnnouncedBeforeStartOrAfterStop() throws Exception {
		ZtpTorTransport t = transport(new Settings());
		List<String> announced = new ArrayList<>();
		t.setTorReconfiguredListener(() -> announced.add("refeed"));
		t.setNetworkEnabled(true);
		t.restartNetwork();
		assertTrue(announced.isEmpty());
		assertTrue(tor.calls.isEmpty());
		t.start(null);
		t.stop();
		announced.clear();
		tor.calls.clear();
		t.setNetworkEnabled(true);
		assertTrue(announced.isEmpty());
		assertTrue(tor.calls.isEmpty());
	}

	@Test(timeout = 15_000)
	public void aFailingListenerDoesNotDisturbTheNetworkHandling()
			throws Exception {
		ZtpTorTransport t = transport(new Settings());
		int[] runs = {0};
		t.setTorReconfiguredListener(() -> {
			runs[0]++;
			throw new IllegalStateException("listener broke");
		});
		t.start(null);
		tor.calls.clear();
		t.setNetworkEnabled(false);
		t.setNetworkEnabled(true);
		assertEquals(Arrays.asList("network:false", "bridges:off",
				"network:true"), tor.calls);
		assertEquals(2, runs[0]);
	}

	@Test(timeout = 15_000)
	public void theListenerRunsAfterTheChangeNotBefore() throws Exception {
		ZtpTorTransport t = transport(new Settings());
		List<String> seenAtRefeed = new ArrayList<>();
		t.setTorReconfiguredListener(
				() -> seenAtRefeed.add(String.join(",", tor.calls)));
		t.start(null);
		tor.calls.clear();
		t.setNetworkEnabled(true);
		assertEquals(Collections.singletonList("bridges:off,network:true"),
				seenAtRefeed);
	}

	@Test(timeout = 15_000)
	public void removingTheListenerStopsTheAnnouncements() throws Exception {
		ZtpTorTransport t = started(new Settings());
		t.setTorReconfiguredListener(null);
		t.setNetworkEnabled(false);
		assertEquals(Collections.singletonList("network:false"), tor.calls);
	}
}

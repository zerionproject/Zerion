package org.zerionproject.transport;

import org.zerionproject.tor.CircumventionProvider;
import org.zerionproject.tor.TorWrapper;
import org.zerionproject.tor.TorWrapper.TorState;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZtpTorTransportRecoveryTest {

	static final String PEER_ONION =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";

	private static class RecordingTor implements TorWrapper {
		final List<Boolean> enableCalls =
				Collections.synchronizedList(new ArrayList<>());
		final List<String> calls =
				Collections.synchronizedList(new ArrayList<>());
		volatile TorState state = TorState.CONNECTING;
		volatile int failStarts = 0;
		volatile boolean refuseBridges = false;
		volatile boolean refuseEnable = false;
		@Nullable
		volatile java.util.concurrent.CountDownLatch holdStart = null;
		private int onions = 0;

		public void start() throws IOException {
			calls.add("start");
			java.util.concurrent.CountDownLatch hold = holdStart;
			if (hold != null) {
				try {
					hold.await(10, java.util.concurrent.TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			if (failStarts > 0) {
				failStarts--;
				throw new IOException("start failed");
			}
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

		@Nullable
		public HiddenServiceProperties publishHiddenService(int localPort,
				int remotePort, @Nullable String privateKey) {
			String key = privateKey == null ? "generated" : privateKey;
			calls.add("publish:" + localPort + ":" + remotePort + ":" + key);
			return hiddenService("onion" + (++onions), key);
		}

		public void removeHiddenService(String onion) {
			calls.add("remove:" + onion);
		}

		public void enableNetwork(boolean enable) throws IOException {
			enableCalls.add(enable);
			calls.add("network:" + enable);
			if (enable && refuseEnable) {
				throw new IOException("Failed to bind one of the listener ports");
			}
		}

		public void enableBridges(List<String> bridges) throws IOException {
			calls.add("bridges:" + bridges.size());
			if (refuseBridges) throw new IOException("552 rejected");
		}

		public void disableBridges() {
			calls.add("bridges:off");
		}

		public void enableConnectionPadding(boolean enable) {
		}

		public void enableIpv6(boolean ipv6Only) {
		}

		public void forgetHiddenServiceDescriptors() {
			calls.add("newnym");
		}

		public File getLyrebirdExecutableFile() {
			return new File(".");
		}
	}

	private static class NoSettings implements SettingsManager {
		public Settings getSettings(String namespace) {
			return new Settings();
		}

		public Settings getSettings(Transaction txn, String namespace) {
			return new Settings();
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

	private static class WithBridges implements SettingsManager {
		public Settings getSettings(String namespace) {
			Settings s = new Settings();
			s.putInt(org.zerionproject.core.api.plugin.TorConstants
					.PREF_TOR_NETWORK, org.zerionproject.core.api.plugin
					.TorConstants.PREF_TOR_NETWORK_WITH_BRIDGES);
			s.put(org.zerionproject.core.api.plugin.TorConstants
					.PREF_TOR_CUSTOM_BRIDGES, "obfs4 1.2.3.4:443 ABCDEF");
			return s;
		}

		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		public void mergeSettings(Settings s, String namespace) {
		}

		public void mergeSettings(Transaction txn, Settings s,
				String namespace) {
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

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final AtomicLong now = new AtomicLong(1_000_000L);
	private final RecordingTor tor = new RecordingTor();
	private final List<Long> sleeps =
			Collections.synchronizedList(new ArrayList<>());
	private final AtomicInteger dialAttempts = new AtomicInteger();
	@Nullable
	private ZtpTorTransport transport;

	private static TorWrapper.HiddenServiceProperties hiddenService(
			String onion, String privKey) {
		try {
			Constructor<TorWrapper.HiddenServiceProperties> c =
					TorWrapper.HiddenServiceProperties.class
							.getDeclaredConstructor(String.class,
									String.class);
			c.setAccessible(true);
			return c.newInstance(onion, privKey);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private final SocketFactory refusingFactory = new SocketFactory() {
		@Override
		public Socket createSocket(String host, int port) throws IOException {
			dialAttempts.incrementAndGet();
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

	private static void waitUntil(java.util.function.BooleanSupplier c)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (!c.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("condition not met in time");
			}
			Thread.sleep(10);
		}
	}

	private ZtpTorTransport started() throws Exception {
		return started(null);
	}

	private ZtpTorTransport started(@Nullable String privateKey)
			throws Exception {
		return started(privateKey, new NoSettings(), new TorProcessWatch());
	}

	private ZtpTorTransport started(@Nullable String privateKey,
			SettingsManager settings, TorProcessWatch watch)
			throws Exception {
		ZtpTorTransport t = built(settings, watch);
		t.start(privateKey);
		assertEquals(asList(true), tor.enableCalls);
		tor.enableCalls.clear();
		tor.calls.clear();
		transport = t;
		return t;
	}

	private ZtpTorTransport built(SettingsManager settings,
			TorProcessWatch watch) {
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
			@Override
			public void handlePaired(TransportId transportId, int contactId,
					boolean incoming, InputStream in, OutputStream out) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) {
			}
		};
		TorBridgeConfigurator bridges = new TorBridgeConfigurator(
				settings, new NoBridges(), () -> "", tor,
				new NoEvents(), exec);
		ZtpTorTransport t = new ZtpTorTransport(tor, refusingFactory,
				refusingFactory, exec, handler, bridges, () -> {
				}, watch);
		t.clock = now::get;
		t.sleeper = sleeps::add;
		return t;
	}

	@After
	public void tearDown() throws Exception {
		if (transport != null) transport.stop();
		exec.shutdownNow();
	}

	@Test
	public void aNetworkBounceRepublishesTheOnionsOnceTorIsConnectedAgain()
			throws Exception {
		ZtpTorTransport t = started(null, new NoSettings(),
				new TorProcessWatch());
		String onion = t.publishHiddenService(1234, 80, "k1").onion;
		int onions = t.publishedOnions().size();
		tor.calls.clear();
		tor.state = TorState.CONNECTED;
		t.setNetworkEnabled(false);
		t.onTorState(TorState.DISABLED);
		assertFalse("nothing is re-added while the network is down",
				tor.calls.stream().anyMatch(c -> c.startsWith("remove:")));
		t.setNetworkEnabled(true);
		t.onTorState(TorState.CONNECTING);
		t.onTorState(TorState.CONNECTED);
		waitUntil(() -> tor.calls.stream()
				.filter(c -> c.startsWith("publish:")).count() >= onions);
		assertEquals("every onion is removed once", onions,
				tor.calls.stream().filter(c -> c.startsWith("remove:")).count());
		assertTrue("the removal precedes the re-add",
				tor.calls.indexOf("remove:" + onion)
						< tor.calls.lastIndexOf("publish:1234:80:k1"));
		t.onTorState(TorState.CONNECTED);
		Thread.sleep(200);
		assertEquals("a repeated CONNECTED does not re-add again", onions,
				tor.calls.stream().filter(c -> c.startsWith("remove:")).count());
	}

	@Test
	public void aDescriptorRefreshSignalsNewnymOnlyWhileConnected()
			throws Exception {
		ZtpTorTransport t = started(null, new NoSettings(),
				new TorProcessWatch());
		tor.state = TorState.CONNECTING;
		t.refreshPeerDescriptors();
		assertFalse("no purge while Tor is still connecting",
				tor.calls.contains("newnym"));
		java.util.concurrent.atomic.AtomicInteger refeeds =
				new java.util.concurrent.atomic.AtomicInteger();
		t.setTorReconfiguredListener(refeeds::incrementAndGet);
		tor.state = TorState.CONNECTED;
		t.refreshPeerDescriptors();
		assertTrue(tor.calls.contains("newnym"));
		assertEquals("the purge drops ephemeral client authorization, so the credentials are fed again",
				1, refeeds.get());
	}

	@Test
	public void aTorDeathIsStillDetectedAfterAStopStartCycle()
			throws Exception {
		TorProcessWatch watch = new TorProcessWatch();
		ZtpTorTransport t = started(null, new NoSettings(), watch);
		t.stop();
		transport = null;
		t.start(null);
		transport = t;
		tor.calls.clear();
		watch.controlConnectionLost();
		waitUntil(() -> tor.calls.contains("start"));
		waitUntil(() -> !t.isTorDead());
	}

	@Test
	public void theNetworkStaysDisabledWhileBridgesAreRefused()
			throws Exception {
		ZtpTorTransport t = started(null, new WithBridges(),
				new TorProcessWatch());
		tor.state = TorState.CONNECTED;
		tor.refuseBridges = true;
		tor.calls.clear();
		t.setNetworkEnabled(true);
		assertTrue(tor.calls.toString(), tor.calls.contains("network:false"));
		assertFalse(tor.calls.toString(), tor.calls.contains("network:true"));
		tor.refuseBridges = false;
		tor.calls.clear();
		t.setNetworkEnabled(true);
		assertEquals(asList("bridges:off", "bridges:1", "network:true"),
				tor.calls);
	}

	@Test
	public void aStopDuringARestartLeavesTorStopped() throws Exception {
		TorProcessWatch watch = new TorProcessWatch();
		ZtpTorTransport t = started(null, new NoSettings(), watch);
		java.util.concurrent.CountDownLatch hold =
				new java.util.concurrent.CountDownLatch(1);
		tor.holdStart = hold;
		tor.calls.clear();
		watch.controlConnectionLost();
		waitUntil(() -> tor.calls.contains("start"));
		t.stop();
		transport = null;
		hold.countDown();
		waitUntil(() -> tor.calls.indexOf("stop") >= 0
				&& tor.calls.lastIndexOf("stop") > tor.calls.indexOf("start"));
		assertFalse("the network was not enabled after the stop",
				tor.calls.subList(tor.calls.indexOf("start"),
						tor.calls.size()).contains("network:true"));
	}

	@Test
	public void torIsStoppedWhenTheNetworkCannotBeEnabled()
			throws Exception {
		ZtpTorTransport t = built(new NoSettings(), new TorProcessWatch());
		tor.refuseEnable = true;
		try {
			t.start(null);
			fail();
		} catch (IOException expected) {
		}
		assertEquals(asList("start", "bridges:off", "network:true", "stop"),
				tor.calls);
		tor.refuseEnable = false;
		tor.calls.clear();
		t.start(null);
		transport = t;
		assertEquals(asList("start", "bridges:off", "network:true"),
				tor.calls.subList(0, 3));
		assertFalse(tor.calls.contains("stop"));
	}

	@Test
	public void enableIsPlainWhileTorHasNeverConnected() throws Exception {
		ZtpTorTransport t = started();
		t.onTorState(TorState.CONNECTING);
		t.setNetworkEnabled(true);
		assertEquals(asList(true), tor.enableCalls);
		assertFalse(t.isNetworkDegraded());
	}

	@Test
	public void connectivityReportBouncesOnlyOnceDegraded()
			throws Exception {
		ZtpTorTransport t = started();
		t.onTorState(TorState.CONNECTED);
		t.onTorState(TorState.CONNECTING);
		t.setNetworkEnabled(true);
		assertEquals("inside the grace period: plain enable",
				asList(true), tor.enableCalls);
		now.addAndGet(ZtpTorTransport.DEGRADED_GRACE_MS - 1);
		t.setNetworkEnabled(true);
		assertEquals(asList(true, true), tor.enableCalls);
		now.addAndGet(1);
		t.setNetworkEnabled(true);
		assertEquals(asList(true, true, false, true), tor.enableCalls);
		t.setNetworkEnabled(true);
		assertEquals("rate limited", asList(true, true, false, true, true),
				tor.enableCalls);
		now.addAndGet(ZtpTorTransport.MIN_RESTART_INTERVAL_MS);
		t.setNetworkEnabled(true);
		assertEquals(asList(true, true, false, true, true, false, true),
				tor.enableCalls);
	}

	@Test
	public void deadTorIsRestartedAndItsServicesRepublished()
			throws Exception {
		ZtpTorTransport t = started("main-key");
		t.publishHiddenService(4444, 80, "channel-key");
		tor.calls.clear();
		assertFalse(t.isTorDead());

		t.onControlConnectionLost();
		waitUntil(() -> !t.isTorDead());

		assertEquals(asList("stop", "start", "bridges:off", "network:true",
				"publish:" + t.getLocalPort() + ":80:main-key",
				"publish:4444:80:channel-key"), tor.calls);
		assertTrue(sleeps.isEmpty());
	}

	@Test
	public void noDialReachesTheSocksPortWhileTorIsDead() throws Exception {
		ZtpTorTransport t = started();
		tor.state = TorState.CONNECTED;
		assertEquals(OverlayTransport.DIAL_NOT_CONNECTED,
				t.dial(1, PEER_ONION, false));
		assertEquals("a live Tor is dialled", 1, dialAttempts.get());

		CountDownLatch release = new CountDownLatch(1);
		t.sleeper = ms -> {
			sleeps.add(ms);
			release.await();
		};
		tor.failStarts = 1;
		t.onControlConnectionLost();
		waitUntil(() -> !sleeps.isEmpty());
		assertTrue(t.isTorDead());
		assertEquals(OverlayTransport.DIAL_NOT_CONNECTED,
				t.dial(1, PEER_ONION, false));
		assertEquals("no dial while dead", 1, dialAttempts.get());

		release.countDown();
		waitUntil(() -> !t.isTorDead());
		t.dial(1, PEER_ONION, false);
		assertEquals("dialled again once Tor is back", 2,
				dialAttempts.get());
	}

	@Test
	public void restartAttemptsBackOffWithDoublingDelays() throws Exception {
		ZtpTorTransport t = started();
		tor.failStarts = 3;
		t.onControlConnectionLost();
		waitUntil(() -> !t.isTorDead());
		assertEquals(asList(ZtpTorTransport.PROCESS_RESTART_BACKOFF_MIN_MS,
				ZtpTorTransport.PROCESS_RESTART_BACKOFF_MIN_MS * 2,
				ZtpTorTransport.PROCESS_RESTART_BACKOFF_MIN_MS * 4), sleeps);
		assertEquals(4, Collections.frequency(tor.calls, "start"));
	}

	@Test
	public void dialIsRefusedUnlessTorIsConnected() throws Exception {
		ZtpTorTransport t = started();
		tor.state = TorState.CONNECTING;
		assertEquals(OverlayTransport.DIAL_NOT_CONNECTED,
				t.dial(1, PEER_ONION, false));
		tor.state = TorState.DISABLED;
		t.dial(1, PEER_ONION, true);
		assertEquals(0, dialAttempts.get());
		tor.state = TorState.CONNECTED;
		t.dial(1, PEER_ONION, true);
		assertEquals(1, dialAttempts.get());
	}

	@Test
	public void controlLossAfterStopStartsNothing() throws Exception {
		ZtpTorTransport t = started();
		t.stop();
		transport = null;
		tor.calls.clear();
		t.onControlConnectionLost();
		Thread.sleep(100);
		assertTrue(tor.calls.isEmpty());
		assertFalse(t.isTorDead());
	}

	@Test
	public void degradedOnlyAfterGraceAndRestartsAreRateLimited()
			throws Exception {
		ZtpTorTransport t = started();
		t.onTorState(TorState.CONNECTED);
		assertFalse(t.isNetworkDegraded());
		t.onTorState(TorState.CONNECTING);
		assertFalse(t.isNetworkDegraded());
		now.addAndGet(ZtpTorTransport.DEGRADED_GRACE_MS - 1);
		assertFalse(t.isNetworkDegraded());
		now.addAndGet(1);
		assertTrue(t.isNetworkDegraded());
		t.restartNetwork();
		assertEquals(asList(false, true), tor.enableCalls);
		t.restartNetwork();
		assertEquals(asList(false, true), tor.enableCalls);
		now.addAndGet(ZtpTorTransport.MIN_RESTART_INTERVAL_MS);
		t.restartNetwork();
		assertEquals(asList(false, true, false, true), tor.enableCalls);
		t.onTorState(TorState.CONNECTED);
		assertFalse(t.isNetworkDegraded());
	}

	@Test
	public void deliberateDisableIsNotDegradation() throws Exception {
		ZtpTorTransport t = started();
		t.onTorState(TorState.CONNECTED);
		t.onTorState(TorState.DISABLED);
		now.addAndGet(ZtpTorTransport.DEGRADED_GRACE_MS * 2);
		assertFalse(t.isNetworkDegraded());
		t.setNetworkEnabled(true);
		assertEquals(asList(true), tor.enableCalls);
	}

	@Test
	public void restartNeverReEnablesADeliberatelyDisabledOrStoppingTor()
			throws Exception {
		ZtpTorTransport t = started();
		t.onTorState(TorState.CONNECTED);
		t.onTorState(TorState.CONNECTING);
		now.addAndGet(ZtpTorTransport.DEGRADED_GRACE_MS);
		assertTrue(t.isNetworkDegraded());
		tor.state = TorState.DISABLED;
		t.restartNetwork();
		assertTrue(tor.enableCalls.isEmpty());
		tor.state = TorState.STOPPING;
		t.setNetworkEnabled(true);
		assertEquals(asList(true), tor.enableCalls);
		tor.state = TorState.CONNECTING;
		t.restartNetwork();
		assertEquals(asList(true, false, true), tor.enableCalls);
	}

	@Test
	public void nothingHappensAfterStop() throws Exception {
		ZtpTorTransport t = started();
		t.onTorState(TorState.CONNECTED);
		t.onTorState(TorState.CONNECTING);
		t.stop();
		transport = null;
		now.addAndGet(ZtpTorTransport.DEGRADED_GRACE_MS);
		assertFalse(t.isNetworkDegraded());
		t.restartNetwork();
		t.setNetworkEnabled(true);
		assertTrue(tor.enableCalls.isEmpty());
	}

	@Test
	public void aRestartLoopOfAnEarlierRunLeavesTheLaterRunAlone()
			throws Exception {
		ZtpTorTransport t = started();
		CountDownLatch release = new CountDownLatch(1);
		t.sleeper = ms -> {
			sleeps.add(ms);
			release.await();
		};
		tor.failStarts = 1;
		t.onControlConnectionLost();
		waitUntil(() -> !sleeps.isEmpty());
		t.stop();
		t.start(null);
		tor.calls.clear();
		release.countDown();
		Thread.sleep(500);
		assertFalse("the earlier run stopped the later run's Tor",
				tor.calls.contains("stop"));
		assertFalse("the earlier run started Tor again",
				tor.calls.contains("start"));
	}

	@Test
	public void onlyAV3OnionAddressIsDialled() throws Exception {
		ZtpTorTransport t = started();
		tor.state = TorState.CONNECTED;
		for (String bad : new String[] {"peer", PEER_ONION + ".onion",
				PEER_ONION.toUpperCase(java.util.Locale.US),
				PEER_ONION.substring(1), PEER_ONION + "a",
				"192.0.2.1", "example.com"}) {
			assertEquals(bad, OverlayTransport.DIAL_NOT_CONNECTED,
					t.dial(1, bad, false));
		}
		assertEquals("a malformed address reached the SOCKS listener", 0,
				dialAttempts.get());
		t.dial(1, PEER_ONION, false);
		assertEquals(1, dialAttempts.get());
	}

	private static class WithNetworkSetting implements SettingsManager {
		private final int network;

		WithNetworkSetting(int network) {
			this.network = network;
		}

		public Settings getSettings(String namespace) {
			Settings s = new Settings();
			s.putInt(org.zerionproject.core.api.plugin.TorConstants
					.PREF_TOR_NETWORK, network);
			return s;
		}

		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		public void mergeSettings(Settings s, String namespace) {
		}

		public void mergeSettings(Transaction txn, Settings s,
				String namespace) {
		}
	}

	@Test
	public void anUnknownNetworkChoiceKeepsTheNetworkDisabled() {
		TorBridgeConfigurator never = new TorBridgeConfigurator(
				new WithNetworkSetting(3), new NoBridges(), () -> "", tor,
				new NoEvents(), exec);
		assertFalse(never.apply());
		assertFalse(tor.calls.contains("bridges:off"));
		TorBridgeConfigurator plain = new TorBridgeConfigurator(
				new WithNetworkSetting(org.zerionproject.core.api.plugin
						.TorConstants.PREF_TOR_NETWORK_WITHOUT_BRIDGES),
				new NoBridges(), () -> "", tor, new NoEvents(), exec);
		assertTrue(plain.apply());
	}
}

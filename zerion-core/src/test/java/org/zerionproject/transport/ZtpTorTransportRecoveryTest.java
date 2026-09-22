package org.zerionproject.transport;

import org.briarproject.onionwrapper.CircumventionProvider;
import org.briarproject.onionwrapper.TorWrapper;
import org.briarproject.onionwrapper.TorWrapper.TorState;
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

/**
 * The recovery contract of the Tor transport after a dead network spell:
 * a connectivity report while Tor is stuck reconnecting bounces Tor's
 * network instead of the idempotent enable, the transport counts as
 * degraded only after the grace period and only after it was once
 * connected, and restarts are rate limited.
 */
public class ZtpTorTransportRecoveryTest {

	private static class RecordingTor implements TorWrapper {
		final List<Boolean> enableCalls =
				Collections.synchronizedList(new ArrayList<>());
		final List<String> calls =
				Collections.synchronizedList(new ArrayList<>());
		volatile TorState state = TorState.CONNECTING;
		volatile int failStarts = 0;
		private int onions = 0;

		public void start() throws IOException {
			calls.add("start");
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

		public void enableNetwork(boolean enable) {
			enableCalls.add(enable);
			calls.add("network:" + enable);
		}

		public void enableBridges(List<String> bridges) {
		}

		public void disableBridges() {
		}

		public void enableConnectionPadding(boolean enable) {
		}

		public void enableIpv6(boolean ipv6Only) {
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

	/** The wrapper's properties type has no public constructor. */
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

	/** A socket factory that counts attempts and never connects. */
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
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
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
				new NoSettings(), new NoBridges(), () -> "", tor,
				new NoEvents(), exec);
		ZtpTorTransport t = new ZtpTorTransport(tor, refusingFactory,
				refusingFactory, exec, handler, bridges, () -> {
				});
		t.clock = now::get;
		t.sleeper = sleeps::add;
		t.start(privateKey);
		assertEquals(asList(true), tor.enableCalls);
		tor.enableCalls.clear();
		tor.calls.clear();
		transport = t;
		return t;
	}

	@After
	public void tearDown() throws Exception {
		if (transport != null) transport.stop();
		exec.shutdownNow();
	}

	@Test
	public void enableIsPlainWhileTorHasNeverConnected() throws Exception {
		ZtpTorTransport t = started();
		t.onTorState(TorState.CONNECTING);
		t.setNetworkEnabled(true);
		assertEquals(asList(true), tor.enableCalls);
		assertFalse(t.isNetworkDegraded());
	}

	/**
	 * NET-08: a connectivity report inside the grace period is a plain
	 * enable, since Tor is only reconnecting after a brief dip and a bounce
	 * would cut the sessions it is about to recover; once the grace period
	 * has passed the report bounces the network, rate limited.
	 */
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

	/**
	 * NET-07: the loss of the control connection while Tor is running is
	 * the death of the tor child. Tor is stopped and started again with the
	 * same settings sequence, and every hidden service the transport had
	 * published is published again with its own key.
	 */
	@Test
	public void deadTorIsRestartedAndItsServicesRepublished()
			throws Exception {
		ZtpTorTransport t = started("main-key");
		t.publishHiddenService(4444, 80, "channel-key");
		tor.calls.clear();
		assertFalse(t.isTorDead());

		t.onControlConnectionLost();
		waitUntil(() -> !t.isTorDead());

		assertEquals(asList("stop", "start", "network:true",
				"publish:" + t.getLocalPort() + ":80:main-key",
				"publish:4444:80:channel-key"), tor.calls);
		assertTrue(sleeps.isEmpty());
	}

	@Test
	public void noDialReachesTheSocksPortWhileTorIsDead() throws Exception {
		ZtpTorTransport t = started();
		tor.state = TorState.CONNECTED;
		assertEquals(OverlayTransport.DIAL_NOT_CONNECTED,
				t.dial(1, "peer", false));
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
				t.dial(1, "peer", false));
		assertEquals("no dial while dead", 1, dialAttempts.get());

		release.countDown();
		waitUntil(() -> !t.isTorDead());
		t.dial(1, "peer", false);
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
				t.dial(1, "peer", false));
		tor.state = TorState.DISABLED;
		t.dial(1, "peer", true);
		assertEquals(0, dialAttempts.get());
		tor.state = TorState.CONNECTED;
		t.dial(1, "peer", true);
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
}

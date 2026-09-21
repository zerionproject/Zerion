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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
		volatile TorState state = TorState.CONNECTING;

		public void start() {
		}

		public void stop() {
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
			return null;
		}

		public void removeHiddenService(String onion) {
		}

		public void enableNetwork(boolean enable) {
			enableCalls.add(enable);
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
	@Nullable
	private ZtpTorTransport transport;

	private ZtpTorTransport started() throws Exception {
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
		ZtpTorTransport t = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				handler, bridges);
		t.clock = now::get;
		t.start(null);
		assertEquals(asList(true), tor.enableCalls);
		tor.enableCalls.clear();
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

	@Test
	public void connectivityReportWhileDegradedBouncesTheNetwork()
			throws Exception {
		ZtpTorTransport t = started();
		t.onTorState(TorState.CONNECTED);
		t.onTorState(TorState.CONNECTING);
		t.setNetworkEnabled(true);
		assertEquals(asList(false, true), tor.enableCalls);
		t.setNetworkEnabled(true);
		assertEquals(asList(false, true, true), tor.enableCalls);
		now.addAndGet(ZtpTorTransport.MIN_RESTART_INTERVAL_MS);
		t.setNetworkEnabled(true);
		assertEquals(asList(false, true, true, false, true), tor.enableCalls);
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

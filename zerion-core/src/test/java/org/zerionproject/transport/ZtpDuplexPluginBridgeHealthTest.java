package org.zerionproject.transport;

import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.FeatureFlags;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.tor.BridgeLines;
import org.zerionproject.tor.BridgeListKeys;
import org.zerionproject.tor.CircumventionProvider.BridgeType;
import org.zerionproject.tor.CircumventionProviderFactory;
import org.zerionproject.tor.TorWrapper.Observer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK_WITH_BRIDGES;
import static org.zerionproject.tor.BridgeListFixtures.DAY_MS;

public class ZtpDuplexPluginBridgeHealthTest {

	@Test
	public void aFailedConnectionToAHandedBridgeReachesItsHealthRecord() {
		AtomicReference<Observer> observer = new AtomicReference<>();
		List<List<String>> handed = new ArrayList<>();
		ZtpTorTransportTest.StubTor tor = new ZtpTorTransportTest.StubTor() {
			@Override
			public void setObserver(@Nullable Observer o) {
				observer.set(o);
			}

			@Override
			public void enableBridges(List<String> bridges) {
				handed.add(new ArrayList<>(bridges));
			}
		};
		Executor direct = Runnable::run;
		TaskScheduler scheduler = new TaskScheduler() {
			@Override
			public Cancellable schedule(Runnable task, Executor executor,
					long delay, TimeUnit unit) {
				return () -> {
				};
			}

			@Override
			public Cancellable scheduleWithFixedDelay(Runnable task,
					Executor executor, long delay, long interval,
					TimeUnit unit) {
				return () -> {
				};
			}
		};
		EventBus bus = new EventBus() {
			@Override
			public void addListener(EventListener l) {
			}

			@Override
			public void removeListener(EventListener l) {
			}

			@Override
			public void broadcast(Event e) {
			}
		};
		NamespacedSettings settings = new NamespacedSettings();
		settings.namespace(TorConstants.ID.getString()).putInt(PREF_TOR_NETWORK,
				PREF_TOR_NETWORK_WITH_BRIDGES);
		NamespacedSettings.TestClock clock =
				new NamespacedSettings.TestClock(20_400 * DAY_MS);
		BridgeHealth health = new BridgeHealth(settings, clock,
				() -> "account");
		TorBridgeConfigurator bridges = new TorBridgeConfigurator(settings,
				CircumventionProviderFactory.createCircumventionProvider(),
				() -> "NL", tor, bus, direct, new FeatureFlags() {
			public boolean shouldEnableImageAttachments() {
				return true;
			}

			public boolean shouldEnableProfilePictures() {
				return true;
			}

			public boolean shouldEnableDisappearingMessages() {
				return true;
			}

			public boolean shouldEnableI2p() {
				return false;
			}

			public boolean shouldEnableDebugCountryOverride() {
				return false;
			}
		}, scheduler, clock, new BridgeListStore(settings, clock,
				BridgeListKeys.pinnedOnly(), () -> "account"), health);
		ZtpTorTransport transport = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), direct,
				null, bridges, () -> {
		});
		new ZtpDuplexPlugin(direct, direct, scheduler,
				SocketFactory.getDefault(), tor, transport, null, null, null,
				null, bus, null, null);

		assertTrue(bridges.apply());
		List<String> first = handed.get(handed.size() - 1);
		assertEquals(CircumventionProviderFactory.createCircumventionProvider()
				.getBridges(BridgeType.DEFAULT_OBFS4, "NL").get(0),
				first.get(0));
		String fp = BridgeLines.fingerprint(first.get(0));
		observer.get().onRelayConnection(fp, false);
		assertTrue(settings.written.contains(BridgeHealth.NAMESPACE));
		assertTrue(bridges.apply());
		List<String> second = handed.get(handed.size() - 1);
		assertFalse(second.contains(first.get(0)));
		assertEquals(first.size() - 1, second.size());
	}
}

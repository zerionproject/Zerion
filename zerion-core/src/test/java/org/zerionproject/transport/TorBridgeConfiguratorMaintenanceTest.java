package org.zerionproject.transport;

import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.FeatureFlags;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TorBootstrapEvent;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.tor.BridgeList;
import org.zerionproject.tor.BridgeListFixtures;
import org.zerionproject.tor.BridgeListFixtures.Signer;
import org.zerionproject.tor.BridgeListKeys;
import org.zerionproject.tor.BridgeLines;
import org.zerionproject.tor.CircumventionProvider;
import org.zerionproject.tor.CircumventionProvider.BridgeType;
import org.zerionproject.tor.CircumventionProviderFactory;
import org.zerionproject.tor.TorWrapper;
import org.zerionproject.transport.BridgeListStore.Verdict;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.plugin.TorConstants.HELPER_TRANSPORTS_USE;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_CUSTOM_BRIDGES;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_HELPER_TRANSPORTS;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_HELPER_TRANSPORTS_COUNTRY;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK_AUTOMATIC;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK_WITH_BRIDGES;
import static org.zerionproject.tor.BridgeListFixtures.DAY_MS;
import static org.zerionproject.tor.BridgeListFixtures.HOUR_MS;
import static org.zerionproject.tor.BridgeListFixtures.bytes;
import static org.zerionproject.tor.BridgeListFixtures.date;
import static org.zerionproject.tor.BridgeListFixtures.groups;
import static org.zerionproject.tor.BridgeListFixtures.json;
import static org.zerionproject.tor.BridgeListFixtures.obfs4;
import static org.zerionproject.tor.BridgeListFixtures.vanilla;

public class TorBridgeConfiguratorMaintenanceTest {

	private static final long TODAY = LocalDate.of(2026, 10, 18).toEpochDay();

	private final CircumventionProvider provider =
			CircumventionProviderFactory.createCircumventionProvider();
	private final Signer ours = new Signer();
	private final Signer theirs = new Signer();
	private final NamespacedSettings settings = new NamespacedSettings();
	private final NamespacedSettings.TestClock clock =
			new NamespacedSettings.TestClock(TODAY * DAY_MS + 9 * HOUR_MS);
	private final RecordingTor tor = new RecordingTor();
	private final FakeScheduler scheduler = new FakeScheduler();
	private final BridgeListStore store = new BridgeListStore(settings, clock,
			new BridgeListKeys(Collections.singletonList(ours.publicKey()),
					Collections.<byte[]>emptyList()), () -> "account");
	private final BridgeHealth health =
			new BridgeHealth(settings, clock, () -> "account");
	private String country = "NL";
	private final TorBridgeConfigurator configurator;

	public TorBridgeConfiguratorMaintenanceTest() {
		tor().putInt(PREF_TOR_NETWORK, PREF_TOR_NETWORK_WITH_BRIDGES);
		configurator = new TorBridgeConfigurator(settings, provider,
				() -> country, tor, new NoEvents(), Runnable::run, new Flags(),
				scheduler, clock, store, health);
	}

	private Settings tor() {
		return settings.namespace(TorConstants.ID.getString());
	}

	private List<String> applied() {
		assertTrue(configurator.apply());
		return new ArrayList<>(tor.lastBridges);
	}

	private List<String> bundled(BridgeType... types) {
		List<String> out = new ArrayList<>();
		for (BridgeType t : types) out.addAll(provider.getBridges(t, country));
		return out;
	}

	private static List<String> prefixed(String... lines) {
		List<String> out = new ArrayList<>();
		for (String l : lines) out.add("Bridge " + l);
		return out;
	}

	private static byte[] list(long seq, long issued, long expires,
			Map<String, List<String>> groups) {
		return bytes(json(seq, date(issued), date(expires), groups));
	}

	private static Map<String, List<String>> threeAndThree() {
		return groups(BridgeList.OBFS4_DEFAULT,
				Arrays.asList(obfs4(1), obfs4(2), obfs4(3)),
				BridgeList.OBFS4_NON_DEFAULT,
				Arrays.asList(obfs4(4), obfs4(5), obfs4(6)),
				BridgeList.VANILLA,
				Arrays.asList(vanilla(1), vanilla(2), vanilla(3)));
	}

	private Verdict offer(byte[] file) {
		return store.offer(file, ours.sign(file), false);
	}

	private static String fp(String line) {
		return BridgeLines.fingerprint(line);
	}

	@Test
	public void aVerifiedListReplacesTheBundledObfs4AndPlainLines() {
		assertEquals(Verdict.ACCEPTED,
				offer(list(2, TODAY, TODAY + 90, threeAndThree())));
		List<String> expected = prefixed(obfs4(1), obfs4(2), obfs4(3),
				vanilla(1), vanilla(2), vanilla(3));
		assertEquals(expected, applied());

		country = "RU";
		tor().putInt(PREF_TOR_NETWORK, PREF_TOR_NETWORK_AUTOMATIC);
		assertEquals(prefixed(obfs4(4), obfs4(5), obfs4(6)), applied());
	}

	@Test
	public void everyRejectedListLeavesTheBundledLines() {
		List<String> baseline = applied();
		assertEquals(bundled(BridgeType.DEFAULT_OBFS4, BridgeType.VANILLA),
				baseline);
		byte[] good = list(3, TODAY, TODAY + 90, threeAndThree());
		byte[] changed = good.clone();
		changed[40] ^= 1;
		assertEquals(Verdict.BAD_SIGNATURE,
				store.offer(changed, ours.sign(good), false));
		assertEquals(baseline, applied());
		assertEquals(Verdict.BAD_SIGNATURE,
				store.offer(good, theirs.sign(good), false));
		assertEquals(baseline, applied());
		assertEquals(Verdict.MALFORMED, offer(bytes(new String(good,
				java.nio.charset.StandardCharsets.US_ASCII)
				.replace("iat-mode=0", "iat-mode=9"))));
		assertEquals(baseline, applied());
		assertEquals(Verdict.EXPIRED,
				offer(list(3, TODAY - 60, TODAY - 1, threeAndThree())));
		assertEquals(baseline, applied());
		assertEquals(Verdict.NOT_YET_VALID,
				offer(list(3, TODAY + 3, TODAY + 60, threeAndThree())));
		assertEquals(baseline, applied());
		assertEquals(Verdict.NOT_NEWER,
				offer(list(1, TODAY, TODAY + 90, threeAndThree())));
		assertEquals(baseline, applied());
	}

	@Test
	public void anAcceptedListThatExpiresFallsBackToTheBundledLines() {
		List<String> baseline = applied();
		assertEquals(Verdict.ACCEPTED,
				offer(list(4, TODAY, TODAY + 2, threeAndThree())));
		assertFalse(baseline.equals(applied()));
		clock.now += 3 * DAY_MS;
		assertEquals(baseline, applied());
	}

	@Test
	public void anOlderListNeverReplacesANewerOne() {
		assertEquals(Verdict.ACCEPTED,
				offer(list(6, TODAY, TODAY + 90, threeAndThree())));
		List<String> newer = applied();
		Map<String, List<String>> other = groups(BridgeList.OBFS4_DEFAULT,
				Arrays.asList(obfs4(30), obfs4(31), obfs4(32)));
		assertEquals(Verdict.NOT_NEWER,
				offer(list(5, TODAY, TODAY + 90, other)));
		assertEquals(newer, applied());
	}

	@Test
	public void aShortRemoteGroupIsToppedUpFromTheBundledLines() {
		assertEquals(Verdict.ACCEPTED, offer(list(2, TODAY, TODAY + 90,
				groups(BridgeList.OBFS4_DEFAULT, Arrays.asList(obfs4(1))))));
		List<String> d = bundled(BridgeType.DEFAULT_OBFS4);
		List<String> expected = new ArrayList<>(prefixed(obfs4(1)));
		expected.addAll(d.subList(0, BridgeHealth.SAFE_MINIMUM - 1));
		expected.addAll(bundled(BridgeType.VANILLA));
		assertEquals(expected, applied());
		assertEquals(expected.subList(0, BridgeHealth.SAFE_MINIMUM),
				TorBridgeConfigurator.withRemoteLines(BridgeType.DEFAULT_OBFS4,
						d, store.current()));
		assertEquals(d, TorBridgeConfigurator.withRemoteLines(
				BridgeType.DEFAULT_OBFS4, d, null));
	}

	@Test
	public void remoteSnowflakeAndMeekLinesAreNeverUsed() {
		Map<String, List<String>> g = threeAndThree();
		g.put("snowflake", Collections.singletonList("snowflake 192.0.2.3:80 "
				+ BridgeListFixtures.fingerprint(1)
				+ " url=https://broker.example/ fronts=front.example"));
		g.put("meek", Collections.singletonList("meek_lite 192.0.2.20:80 "
				+ "url=https://meek.example front=front.example"));
		assertEquals(Verdict.ACCEPTED, offer(list(2, TODAY, TODAY + 90, g)));

		country = "TM";
		tor().putInt(PREF_TOR_NETWORK, PREF_TOR_NETWORK_AUTOMATIC);
		List<String> asked = applied();
		for (String line : asked) {
			assertFalse(line, line.startsWith("Bridge snowflake"));
			assertFalse(line, line.startsWith("Bridge meek_lite"));
		}
		tor().putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_USE);
		tor().put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "TM");
		List<String> consented = applied();
		List<String> helpers = new ArrayList<>();
		for (String line : consented) {
			assertFalse(line, line.contains("example"));
			if (line.startsWith("Bridge snowflake")
					|| line.startsWith("Bridge meek_lite")) {
				helpers.add(line);
			}
		}
		assertEquals(bundled(BridgeType.MEEK, BridgeType.SNOWFLAKE), helpers);
	}

	@Test
	public void aFailingBridgeIsLeftOutAndRetriedAfterItsBackoff() {
		List<String> all = applied();
		assertEquals(5, all.size());
		String bad = all.get(0);
		configurator.onRelayConnection(fp(bad), false);
		List<String> fewer = applied();
		assertFalse(fewer.contains(bad));
		assertEquals(4, fewer.size());
		assertTrue(configurator.retryChecksRunning());
		assertEquals(TorBridgeConfigurator.RETRY_CHECK_MS,
				scheduler.tasks.get(0)[1]);

		int calls = tor.calls.size();
		clock.now += HOUR_MS;
		scheduler.tick();
		assertEquals("still resting", calls, tor.calls.size());
		clock.now += HOUR_MS;
		scheduler.tick();
		assertEquals("the retry check applied the bridges again", calls + 1,
				tor.calls.size());
		assertTrue(tor.lastBridges.contains(bad));
		assertEquals("a retried bridge comes after the healthy ones", bad,
				tor.lastBridges.get(4));
		assertFalse("nothing left out, so no more checks",
				configurator.retryChecksRunning());
	}

	@Test
	public void whenEveryDownloadedBridgeFailsTheBundledOnesComeBack() {
		assertEquals(Verdict.ACCEPTED,
				offer(list(2, TODAY, TODAY + 90, threeAndThree())));
		List<String> remote = applied();
		for (String line : remote) {
			configurator.onRelayConnection(fp(line), false);
		}
		List<String> bundled =
				bundled(BridgeType.DEFAULT_OBFS4, BridgeType.VANILLA);
		assertEquals("the bundled bridges stand in while the downloaded ones"
				+ " rest", bundled, applied());
		clock.now += 2 * HOUR_MS;
		List<String> retried = applied();
		assertEquals(bundled, retried.subList(0, bundled.size()));
		assertEquals("the downloaded ones are tried again next to them",
				remote, retried.subList(bundled.size(), retried.size()));
		configurator.onRelayConnection(fp(remote.get(0)), true);
		assertEquals("one works again, so the list is used alone", remote,
				applied());
	}

	@Test
	public void aConnectedTorIsNotDisturbedByARetry() {
		List<String> all = applied();
		configurator.onRelayConnection(fp(all.get(1)), false);
		applied();
		configurator.eventOccurred(new TorBootstrapEvent(100));
		int calls = tor.calls.size();
		clock.now += 3 * HOUR_MS;
		scheduler.tick();
		assertEquals(calls, tor.calls.size());
		configurator.torStopped();
		assertFalse(configurator.retryChecksRunning());
		assertEquals(0, scheduler.live());
	}

	@Test
	public void neverFewerThanThreeAndAllWhenAllFail() {
		List<String> all = applied();
		for (int i = 0; i < 4; i++) {
			configurator.onRelayConnection(fp(all.get(i)), false);
		}
		List<String> few = applied();
		assertEquals(BridgeHealth.SAFE_MINIMUM, few.size());
		assertTrue(few.contains(all.get(4)));
		configurator.onRelayConnection(fp(all.get(4)), false);
		List<String> stillFew = applied();
		assertEquals("every bridge fails, so every bridge is tried", all,
				stillFew);
	}

	@Test
	public void aRestingBridgeComesBackOnlyOnceItIsTriedAndWorks() {
		List<String> all = applied();
		configurator.onRelayConnection(fp(all.get(2)), false);
		assertFalse(applied().contains(all.get(2)));
		configurator.onRelayConnection(fp(all.get(2)), true);
		assertEquals("not handed to Tor, so a report about it is not counted",
				4, applied().size());
		assertTrue(configurator.retryChecksRunning());
		health.record(fp(all.get(2)), true);
		assertEquals(all, applied());
		assertFalse(configurator.retryChecksRunning());
	}

	@Test
	public void onlyBridgesHandedToTorAreRecorded() {
		configurator.onRelayConnection(BridgeListFixtures.fingerprint(999),
				false);
		assertFalse(settings.written.contains(BridgeHealth.NAMESPACE));
		List<String> all = applied();
		configurator.onRelayConnection(BridgeListFixtures.fingerprint(999),
				false);
		configurator.onRelayConnection(BridgeListFixtures.fingerprint(999),
				true);
		assertFalse(settings.written.contains(BridgeHealth.NAMESPACE));
		configurator.onRelayConnection(fp(all.get(0)), true);
		assertTrue(settings.written.contains(BridgeHealth.NAMESPACE));
		configurator.torStopped();
		settings.written.clear();
		configurator.onRelayConnection(fp(all.get(1)), false);
		assertFalse("after a stop nothing is recorded",
				settings.written.contains(BridgeHealth.NAMESPACE));
	}

	@Test
	public void customBridgesAreNeverFilteredOrReplaced() {
		assertEquals(Verdict.ACCEPTED,
				offer(list(2, TODAY, TODAY + 90, threeAndThree())));
		String custom = obfs4(40) + "\n" + vanilla(40);
		tor().put(PREF_TOR_CUSTOM_BRIDGES, custom);
		List<String> lines = applied();
		assertEquals(prefixed(obfs4(40), vanilla(40)), lines);
		health.record(fp(obfs4(40)), false);
		configurator.onRelayConnection(fp(obfs4(40)), false);
		assertEquals(prefixed(obfs4(40), vanilla(40)), applied());
		assertFalse(configurator.retryChecksRunning());
	}

	@Test
	public void onlyBuiltInBridgesCountAsBridgeUse() {
		tor().putInt(PREF_TOR_NETWORK, PREF_TOR_NETWORK_AUTOMATIC);
		assertTrue(configurator.apply());
		assertEquals(Arrays.asList("bridges:off"), tor.calls);
		assertFalse("a direct connection is no bridge use",
				store.builtInBridgesRecent());
		tor().put(PREF_TOR_CUSTOM_BRIDGES, obfs4(40));
		tor().putInt(PREF_TOR_NETWORK, PREF_TOR_NETWORK_WITH_BRIDGES);
		applied();
		assertFalse("custom bridges alone are not either",
				store.builtInBridgesRecent());
		tor().remove(PREF_TOR_CUSTOM_BRIDGES);
		applied();
		assertTrue("bridges chosen by the user count",
				store.builtInBridgesRecent());

		NamespacedSettings fresh = new NamespacedSettings();
		fresh.namespace(TorConstants.ID.getString()).putInt(PREF_TOR_NETWORK,
				PREF_TOR_NETWORK_AUTOMATIC);
		BridgeListStore other = new BridgeListStore(fresh, clock,
				BridgeListKeys.pinnedOnly(), () -> "account");
		TorBridgeConfigurator automatic = new TorBridgeConfigurator(fresh,
				provider, () -> "IR", tor, new NoEvents(), Runnable::run,
				new Flags(), scheduler, clock, other,
				new BridgeHealth(fresh, clock, () -> "account"));
		assertTrue(automatic.apply());
		assertTrue("bridges turned on for the country count",
				other.builtInBridgesRecent());
	}

	private boolean healthIsGone(NamespacedSettings s) {
		Settings h = s.namespace(BridgeHealth.NAMESPACE);
		return !h.containsKey(BridgeHealth.KEY_RECORDS)
				&& !h.containsKey(BridgeHealth.KEY_SECRET)
				&& !h.containsKey(BridgeHealth.KEY_OWNER);
	}

	@Test
	public void oldHealthIsDeletedAlsoWhileBridgesAreOff() {
		List<String> all = applied();
		configurator.onRelayConnection(fp(all.get(0)), false);
		assertFalse(healthIsGone(settings));
		tor().putInt(PREF_TOR_NETWORK, PREF_TOR_NETWORK_AUTOMATIC);
		assertTrue(configurator.apply());
		assertFalse("still recent", healthIsGone(settings));
		clock.now += (BridgeHealth.FORGET_AFTER_DAYS + 1) * DAY_MS;
		assertTrue(configurator.apply());
		assertTrue("deleted with its secret on a direct connection",
				healthIsGone(settings));
	}

	@Test
	public void oldHealthIsDeletedAlsoWithCustomBridgesOnly() {
		List<String> all = applied();
		configurator.onRelayConnection(fp(all.get(0)), false);
		tor().put(PREF_TOR_CUSTOM_BRIDGES, obfs4(40));
		applied();
		assertFalse(healthIsGone(settings));
		clock.now += (BridgeHealth.FORGET_AFTER_DAYS + 1) * DAY_MS;
		applied();
		assertTrue("deleted while custom bridges are used",
				healthIsGone(settings));
	}

	@Test
	public void aRestoredCopyOnADirectPhoneDropsItsHealthAtOnce() {
		List<String> all = applied();
		configurator.onRelayConnection(fp(all.get(0)), false);
		assertFalse(healthIsGone(settings));
		settings.namespace(TorConstants.ID.getString()).putInt(
				PREF_TOR_NETWORK, PREF_TOR_NETWORK_AUTOMATIC);
		TorBridgeConfigurator restored = new TorBridgeConfigurator(settings,
				provider, () -> "NL", tor, new NoEvents(), Runnable::run,
				new Flags(), scheduler, clock, store,
				new BridgeHealth(settings, clock, () -> "imported"));
		assertTrue(restored.apply());
		assertEquals("a direct connection", "bridges:off",
				tor.calls.get(tor.calls.size() - 1));
		assertTrue("the copied health is deleted", healthIsGone(settings));
	}

	@Test
	public void nothingOfThisIsWrittenToTheTorSettings() {
		assertEquals(Verdict.ACCEPTED,
				offer(list(2, TODAY, TODAY + 90, threeAndThree())));
		List<String> all = applied();
		configurator.onRelayConnection(fp(all.get(0)), false);
		configurator.onRelayConnection(fp(all.get(1)), true);
		applied();
		assertFalse(settings.written.contains(TorConstants.ID.getString()));
	}

	private static class Flags implements FeatureFlags {
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
	}

	private static class NoEvents implements EventBus {
		public void addListener(EventListener l) {
		}

		public void removeListener(EventListener l) {
		}

		public void broadcast(Event e) {
		}
	}

	private static class FakeScheduler implements TaskScheduler {
		final List<Object[]> tasks = new ArrayList<>();

		public Cancellable schedule(Runnable task, Executor executor,
				long delay, TimeUnit unit) {
			return scheduleWithFixedDelay(task, executor, delay, 0, unit);
		}

		public Cancellable scheduleWithFixedDelay(Runnable task,
				Executor executor, long delay, long interval, TimeUnit unit) {
			Object[] t = {task, unit.toMillis(delay), unit.toMillis(interval),
					Boolean.FALSE};
			tasks.add(t);
			return () -> t[3] = Boolean.TRUE;
		}

		int live() {
			int n = 0;
			for (Object[] t : tasks) if (t[3] == Boolean.FALSE) n++;
			return n;
		}

		void tick() {
			for (Object[] t : new ArrayList<>(tasks)) {
				if (t[3] == Boolean.FALSE) ((Runnable) t[0]).run();
			}
		}
	}

	private static class RecordingTor implements TorWrapper {
		final List<String> calls = new ArrayList<>();
		List<String> lastBridges = new ArrayList<>();

		public void start() {
		}

		public void stop() {
		}

		public void setObserver(@Nullable Observer observer) {
		}

		public TorState getTorState() {
			return TorState.STARTED;
		}

		public boolean isTorRunning() {
			return true;
		}

		public HiddenServiceProperties publishHiddenService(int localPort,
				int remotePort, @Nullable String privateKey) {
			return new HiddenServiceProperties("", "");
		}

		public void removeHiddenService(String onion) {
		}

		public void enableNetwork(boolean enable) {
		}

		public void enableBridges(List<String> bridges) {
			calls.add("bridges");
			lastBridges = new ArrayList<>(bridges);
		}

		public void disableBridges() {
			calls.add("bridges:off");
			lastBridges = new ArrayList<>();
		}

		public void enableConnectionPadding(boolean enable) {
		}

		public void enableIpv6(boolean ipv6Only) {
		}

		public File getLyrebirdExecutableFile() {
			return new File(".");
		}
	}
}

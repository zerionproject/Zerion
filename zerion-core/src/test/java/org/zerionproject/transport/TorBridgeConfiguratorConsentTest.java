package org.zerionproject.transport;

import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.FeatureFlags;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TorBootstrapEvent;
import org.zerionproject.core.api.plugin.event.TorHelperTransportsPromptEvent;
import org.zerionproject.core.api.plugin.event.TransportStateEvent;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.tor.CircumventionProvider;
import org.zerionproject.tor.CircumventionProvider.BridgeType;
import org.zerionproject.tor.CircumventionProviderFactory;
import org.zerionproject.tor.TorWrapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.api.plugin.Plugin.State.STARTING_STOPPING;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.plugin.TorConstants.HELPER_TRANSPORTS_ASK;
import static org.zerionproject.core.api.plugin.TorConstants.HELPER_TRANSPORTS_OFF;
import static org.zerionproject.core.api.plugin.TorConstants.HELPER_TRANSPORTS_USE;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_CUSTOM_BRIDGES;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_DEBUG_COUNTRY;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_HELPER_TRANSPORTS;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_HELPER_TRANSPORTS_COUNTRY;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK_AUTOMATIC;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK_WITH_BRIDGES;

public class TorBridgeConfiguratorConsentTest {

	private final CircumventionProvider provider =
			CircumventionProviderFactory.createCircumventionProvider();

	private static class FakeScheduler implements TaskScheduler {
		final List<Object[]> tasks = new ArrayList<>();
		int cancelled = 0;

		public Cancellable schedule(Runnable task, Executor executor,
				long delay, TimeUnit unit) {
			return scheduleWithFixedDelay(task, executor, delay, 0, unit);
		}

		public Cancellable scheduleWithFixedDelay(Runnable task,
				Executor executor, long delay, long interval, TimeUnit unit) {
			Object[] t = {task, unit.toMillis(delay), unit.toMillis(interval),
					Boolean.FALSE};
			tasks.add(t);
			return () -> {
				t[3] = Boolean.TRUE;
				cancelled++;
			};
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

	private static class Fixture {
		final Settings settings = new Settings();
		final List<Event> events = new ArrayList<>();
		final RecordingTor tor = new RecordingTor();
		final FakeScheduler scheduler = new FakeScheduler();
		long now = 1_000_000_000_000L;
		String country = "NL";
		boolean debugOverride = false;
		TorBridgeConfigurator configurator;

		Fixture(CircumventionProvider provider) {
			settings.putInt(PREF_TOR_NETWORK, PREF_TOR_NETWORK_AUTOMATIC);
			FeatureFlags flags = new FeatureFlags() {
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
					return debugOverride;
				}
			};
			configurator = new TorBridgeConfigurator(
					new FixedSettings(settings), provider, () -> country,
					tor, new RecordingEvents(events), Runnable::run, flags,
					scheduler, new Clock() {
						public long currentTimeMillis() {
							return now;
						}

						public void sleep(long milliseconds) {
						}
					});
		}

		List<String> applied() {
			assertTrue(configurator.apply());
			return tor.lastBridges;
		}
	}

	private static boolean has(List<String> lines, String transport) {
		for (String l : lines) {
			if (l.startsWith("Bridge " + transport + " ")) return true;
		}
		return false;
	}

	private static int prompts(List<Event> events) {
		int n = 0;
		for (Event e : events) {
			if (e instanceof TorHelperTransportsPromptEvent) n++;
		}
		return n;
	}

	@Test
	public void withoutAnAnswerOnlyObfs4AndPlainLinesAreUsedAndAPromptIsDue() {
		for (String cc : new String[] {"BY", "CN", "EG", "HK", "IR", "MM",
				"RU", "TM"}) {
			Fixture f = new Fixture(provider);
			f.country = cc;
			List<String> lines = f.applied();
			assertFalse(cc, lines.isEmpty());
			assertTrue(cc, has(lines, "obfs4"));
			assertFalse(cc, has(lines, "snowflake"));
			assertFalse(cc, has(lines, "meek_lite"));
			assertEquals(cc, cc, f.configurator.pendingHelperPrompt());
			assertEquals(cc, 1, prompts(f.events));
			assertEquals(cc, ((TorHelperTransportsPromptEvent) f.events.get(0))
					.getCountry());
		}
	}

	@Test
	public void thePromptIsBroadcastOnceUntilAnswered() {
		Fixture f = new Fixture(provider);
		f.country = "RU";
		f.applied();
		f.applied();
		f.applied();
		assertEquals(1, prompts(f.events));
		assertEquals("RU", f.configurator.pendingHelperPrompt());
	}

	@Test
	public void consentForTheCountryAddsSnowflakeAndMeek() {
		Fixture f = new Fixture(provider);
		f.country = "TM";
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_USE);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "TM");
		List<String> lines = f.applied();
		assertTrue(has(lines, "obfs4"));
		assertTrue(has(lines, "snowflake"));
		assertTrue(has(lines, "meek_lite"));
		assertNull(f.configurator.pendingHelperPrompt());
		assertEquals(0, prompts(f.events));
	}

	@Test
	public void decliningKeepsObfs4OnlyAndDoesNotAskAgain() {
		Fixture f = new Fixture(provider);
		f.country = "CN";
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_OFF);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "CN");
		List<String> lines = f.applied();
		assertTrue(has(lines, "obfs4"));
		assertFalse(has(lines, "snowflake"));
		assertNull(f.configurator.pendingHelperPrompt());
		assertEquals(0, prompts(f.events));
	}

	@Test
	public void anAnswerIsAppliedWithoutARestartWhenSettingsChange() {
		Fixture f = new Fixture(provider);
		f.country = "IR";
		List<String> before = f.applied();
		assertFalse(has(before, "snowflake"));
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_USE);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "IR");
		List<String> after = f.applied();
		assertTrue(has(after, "snowflake"));
		assertNull(f.configurator.pendingHelperPrompt());
		assertEquals(Arrays.asList("bridges", "bridges"), f.tor.calls);
	}

	@Test
	public void aCountryChangeAsksAgain() {
		Fixture f = new Fixture(provider);
		f.country = "RU";
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_USE);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "RU");
		assertTrue(has(f.applied(), "snowflake"));
		assertEquals(0, prompts(f.events));
		f.country = "CN";
		List<String> lines = f.applied();
		assertFalse(has(lines, "snowflake"));
		assertEquals("CN", f.configurator.pendingHelperPrompt());
		assertEquals(1, prompts(f.events));
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_OFF);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "RU");
		f.country = "CN";
		assertFalse(has(f.applied(), "snowflake"));
		assertEquals("CN", f.configurator.pendingHelperPrompt());
	}

	@Test
	public void noPromptWhereBridgesAreNotNeeded() {
		for (String cc : new String[] {"NL", "US", "DE", "ZZ", ""}) {
			Fixture f = new Fixture(provider);
			f.country = cc;
			assertTrue(f.configurator.apply());
			assertNull(cc, f.configurator.pendingHelperPrompt());
			assertEquals(cc, 0, prompts(f.events));
			assertEquals(cc, Arrays.asList("bridges:off"), f.tor.calls);
		}
	}

	@Test
	public void manualBridgesAskTooAndCustomLinesNeverDo() {
		Fixture f = new Fixture(provider);
		f.country = "CN";
		f.settings.putInt(PREF_TOR_NETWORK, PREF_TOR_NETWORK_WITH_BRIDGES);
		List<String> lines = f.applied();
		assertTrue(has(lines, "obfs4"));
		assertFalse(has(lines, "snowflake"));
		assertEquals("CN", f.configurator.pendingHelperPrompt());
		assertEquals(1, prompts(f.events));
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_USE);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "CN");
		assertTrue(has(f.applied(), "snowflake"));
		assertNull(f.configurator.pendingHelperPrompt());

		Fixture m = new Fixture(provider);
		m.country = "NL";
		m.settings.putInt(PREF_TOR_NETWORK, PREF_TOR_NETWORK_WITH_BRIDGES);
		List<String> manual = m.applied();
		assertTrue(has(manual, "obfs4"));
		assertFalse(has(manual, "snowflake"));
		assertNull(m.configurator.pendingHelperPrompt());
		assertEquals(0, prompts(m.events));

		Fixture g = new Fixture(provider);
		g.country = "CN";
		g.settings.put(PREF_TOR_CUSTOM_BRIDGES,
				"snowflake 192.0.2.9:80 AAAA url=https://example.invalid/");
		List<String> custom = g.applied();
		assertEquals(1, custom.size());
		assertNull(g.configurator.pendingHelperPrompt());
		assertEquals(0, prompts(g.events));
	}

	@Test
	public void aPromptIsWithdrawnWhenBridgesAreNoLongerNeeded() {
		Fixture f = new Fixture(provider);
		f.country = "RU";
		f.applied();
		assertEquals("RU", f.configurator.pendingHelperPrompt());
		f.country = "NL";
		assertTrue(f.configurator.apply());
		assertNull(f.configurator.pendingHelperPrompt());
	}

	@Test
	public void theDebugCountryOverrideOnlyCountsWhenTheFlagAllowsIt() {
		Fixture f = new Fixture(provider);
		f.country = "NL";
		f.settings.put(PREF_TOR_DEBUG_COUNTRY, "cn");
		assertTrue(f.configurator.apply());
		assertEquals(Arrays.asList("bridges:off"), f.tor.calls);
		assertEquals("NL", f.configurator.lastCountry());

		Fixture g = new Fixture(provider);
		g.country = "NL";
		g.debugOverride = true;
		g.settings.put(PREF_TOR_DEBUG_COUNTRY, "cn");
		List<String> lines = g.applied();
		assertTrue(has(lines, "obfs4"));
		assertFalse(has(lines, "snowflake"));
		assertEquals("CN", g.configurator.pendingHelperPrompt());
		assertEquals("CN", g.configurator.lastCountry());

		Fixture h = new Fixture(provider);
		h.country = "NL";
		h.debugOverride = true;
		h.settings.put(PREF_TOR_DEBUG_COUNTRY, "nope");
		assertTrue(h.configurator.apply());
		assertEquals(Arrays.asList("bridges:off"), h.tor.calls);
	}

	@Test
	public void aChoiceMadeOutsideABridgeCountryHoldsEverywhere() {
		Fixture f = new Fixture(provider);
		f.country = "NL";
		assertTrue(f.configurator.apply());
		assertEquals(TorBridgeConfigurator.ANY_COUNTRY,
				f.configurator.helperChoiceCountry());
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_USE);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY,
				TorBridgeConfigurator.ANY_COUNTRY);
		f.country = "IR";
		List<String> lines = f.applied();
		assertTrue(has(lines, "snowflake"));
		assertNull(f.configurator.pendingHelperPrompt());
		assertEquals(0, prompts(f.events));
		assertEquals("IR", f.configurator.helperChoiceCountry());

		Fixture g = new Fixture(provider);
		g.country = "NL";
		g.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_OFF);
		g.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY,
				TorBridgeConfigurator.ANY_COUNTRY);
		g.country = "TM";
		assertFalse(has(g.applied(), "meek_lite"));
		assertNull(g.configurator.pendingHelperPrompt());
		assertEquals(0, prompts(g.events));
	}

	@Test
	public void anAnswerClearsThePendingPromptAtOnce() {
		Fixture f = new Fixture(provider);
		f.country = "RU";
		f.applied();
		assertEquals("RU", f.configurator.pendingHelperPrompt());
		f.configurator.clearHelperPrompt();
		assertNull(f.configurator.pendingHelperPrompt());
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_OFF);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "RU");
		f.applied();
		assertNull(f.configurator.pendingHelperPrompt());
		assertEquals(1, prompts(f.events));
	}

	@Test
	public void afterNotNowAStalledBootstrapAsksAgainAtMostEveryHalfHour() {
		Fixture f = new Fixture(provider);
		f.country = "RU";
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_OFF);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "RU");
		assertFalse(has(f.applied(), "snowflake"));
		assertEquals(1, f.scheduler.live());
		assertEquals(TorBridgeConfigurator.STALL_CHECK_MS,
				f.scheduler.tasks.get(0)[1]);
		assertEquals(TorBridgeConfigurator.STALL_CHECK_MS,
				f.scheduler.tasks.get(0)[2]);
		f.applied();
		assertEquals("applying again keeps the one check", 1,
				f.scheduler.live());

		f.configurator.eventOccurred(new TorBootstrapEvent(25));
		f.now += TorBridgeConfigurator.STALL_CHECK_MS;
		f.scheduler.tick();
		assertEquals(1, prompts(f.events));
		assertEquals("RU", f.configurator.pendingHelperPrompt());
		assertFalse("never granted on its own",
				has(f.applied(), "snowflake"));

		f.now += TorBridgeConfigurator.STALL_CHECK_MS;
		f.scheduler.tick();
		assertEquals("not again within half an hour", 1, prompts(f.events));
		f.now += TorBridgeConfigurator.REPROMPT_INTERVAL_MS;
		f.scheduler.tick();
		assertEquals(2, prompts(f.events));

		f.configurator.eventOccurred(new TorBootstrapEvent(100));
		f.now += TorBridgeConfigurator.REPROMPT_INTERVAL_MS;
		f.scheduler.tick();
		assertEquals("connected, so no prompt", 2, prompts(f.events));

		f.configurator.torStopped();
		assertEquals("a stop ends the checks", 0, f.scheduler.live());
		f.now += TorBridgeConfigurator.REPROMPT_INTERVAL_MS;
		f.scheduler.tick();
		assertEquals(2, prompts(f.events));
		f.applied();
		assertEquals("the restart arms them again", 1, f.scheduler.live());
		f.now += TorBridgeConfigurator.STALL_CHECK_MS;
		f.scheduler.tick();
		assertEquals("a restart starts from zero", 3, prompts(f.events));

		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_USE);
		assertTrue(has(f.applied(), "snowflake"));
		assertEquals("consent stops the checks", 0, f.scheduler.live());
		f.now += TorBridgeConfigurator.REPROMPT_INTERVAL_MS;
		f.scheduler.tick();
		assertEquals(3, prompts(f.events));
	}

	@Test
	public void stoppingTorEndsTheStallChecks() {
		Fixture f = new Fixture(provider);
		f.country = "RU";
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_OFF);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "RU");
		f.applied();
		assertTrue(f.configurator.stallChecksRunning());
		f.configurator.torStopped();
		assertFalse(f.configurator.stallChecksRunning());
		assertEquals(0, f.scheduler.live());
		f.now += TorBridgeConfigurator.REPROMPT_INTERVAL_MS;
		f.scheduler.tick();
		assertEquals(0, prompts(f.events));
		f.configurator.torStopped();
		assertEquals(0, f.scheduler.live());
	}

	@Test
	public void aLateStopEventDoesNotUndoAQuickRestart() {
		Fixture f = new Fixture(provider);
		f.country = "RU";
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_OFF);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "RU");
		f.applied();
		assertEquals(1, f.scheduler.live());
		f.configurator.torStopped();
		assertEquals(0, f.scheduler.live());
		f.applied();
		assertEquals("the restart arms the check", 1, f.scheduler.live());
		f.configurator.eventOccurred(new TorBootstrapEvent(100));
		f.configurator.eventOccurred(new TransportStateEvent(TorConstants.ID,
				STARTING_STOPPING));
		assertEquals("the late stop event is ignored", 1,
				f.scheduler.live());
		assertTrue(f.configurator.stallChecksRunning());
		f.now += TorBridgeConfigurator.STALL_CHECK_MS;
		f.scheduler.tick();
		assertEquals("and did not reset the bootstrap", 0,
				prompts(f.events));

		Fixture g = new Fixture(provider);
		g.country = "RU";
		g.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_OFF);
		g.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "RU");
		g.applied();
		g.configurator.torStopped();
		g.configurator.eventOccurred(new TransportStateEvent(TorConstants.ID,
				STARTING_STOPPING));
		assertEquals("a stop event with no apply since the stop is harmless",
				0, g.scheduler.live());
	}

	@Test
	public void leavingTheCountryOrBridgesStopsTheStallChecks() {
		Fixture f = new Fixture(provider);
		f.country = "CN";
		f.settings.putInt(PREF_TOR_HELPER_TRANSPORTS, HELPER_TRANSPORTS_OFF);
		f.settings.put(PREF_TOR_HELPER_TRANSPORTS_COUNTRY, "CN");
		f.applied();
		assertEquals(1, f.scheduler.live());
		f.country = "NL";
		assertTrue(f.configurator.apply());
		assertEquals(0, f.scheduler.live());
		f.country = "CN";
		f.applied();
		assertEquals(1, f.scheduler.live());
		f.settings.put(PREF_TOR_CUSTOM_BRIDGES, "192.0.2.9:443 AAAA");
		f.applied();
		assertEquals(0, f.scheduler.live());
	}

	@Test
	public void helperSelectionHelpers() {
		List<BridgeType> all = Arrays.asList(BridgeType.NON_DEFAULT_OBFS4,
				BridgeType.MEEK, BridgeType.SNOWFLAKE);
		assertTrue(TorBridgeConfigurator.needsHelperTransports(all));
		assertFalse(TorBridgeConfigurator.needsHelperTransports(
				Arrays.asList(BridgeType.DEFAULT_OBFS4, BridgeType.VANILLA)));
		assertEquals(Arrays.asList(BridgeType.NON_DEFAULT_OBFS4),
				TorBridgeConfigurator.withHelperConsent(all,
						HELPER_TRANSPORTS_ASK, null, "TM"));
		assertEquals(Arrays.asList(BridgeType.NON_DEFAULT_OBFS4),
				TorBridgeConfigurator.withHelperConsent(all,
						HELPER_TRANSPORTS_OFF, "TM", "TM"));
		assertEquals(Arrays.asList(BridgeType.NON_DEFAULT_OBFS4),
				TorBridgeConfigurator.withHelperConsent(all,
						HELPER_TRANSPORTS_USE, "RU", "TM"));
		assertEquals(all, TorBridgeConfigurator.withHelperConsent(all,
				HELPER_TRANSPORTS_USE, "TM", "TM"));
		assertFalse(TorBridgeConfigurator.helperChoiceApplies(
				HELPER_TRANSPORTS_ASK, "TM", "TM"));
		assertTrue(TorBridgeConfigurator.helperChoiceApplies(
				HELPER_TRANSPORTS_OFF, "TM", "TM"));
		assertTrue(TorBridgeConfigurator.helperChoiceApplies(
				HELPER_TRANSPORTS_USE, TorBridgeConfigurator.ANY_COUNTRY,
				"TM"));
		assertFalse(TorBridgeConfigurator.helperChoiceApplies(
				HELPER_TRANSPORTS_ASK, TorBridgeConfigurator.ANY_COUNTRY,
				"TM"));
		assertEquals(all, TorBridgeConfigurator.withHelperConsent(all,
				HELPER_TRANSPORTS_USE, TorBridgeConfigurator.ANY_COUNTRY,
				"TM"));
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
			lastBridges = bridges;
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

	private static class RecordingEvents implements EventBus {
		private final List<Event> events;

		RecordingEvents(List<Event> events) {
			this.events = events;
		}

		public void addListener(EventListener l) {
		}

		public void removeListener(EventListener l) {
		}

		public void broadcast(Event e) {
			events.add(e);
		}
	}
}

package org.zerionproject.transport;

import org.zerionproject.tor.BridgeLines;
import org.zerionproject.tor.BridgeList;
import org.zerionproject.tor.BridgeListKeys;
import org.zerionproject.tor.CircumventionProvider;
import org.zerionproject.tor.CircumventionProvider.BridgeType;
import org.zerionproject.tor.LocationUtils;
import org.zerionproject.tor.TorWrapper;
import org.zerionproject.core.api.FeatureFlags;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TorBootstrapEvent;
import org.zerionproject.core.api.plugin.event.TorHelperTransportsPromptEvent;
import org.zerionproject.core.api.plugin.event.TransportStateEvent;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.settings.event.SettingsUpdatedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.util.Locale.US;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.zerionproject.core.api.plugin.Plugin.State.STARTING_STOPPING;
import static org.zerionproject.core.api.plugin.TorConstants.DEFAULT_PREF_TOR_NETWORK;
import static org.zerionproject.core.api.plugin.TorConstants.HELPER_TRANSPORTS_ASK;
import static org.zerionproject.core.api.plugin.TorConstants.HELPER_TRANSPORTS_OFF;
import static org.zerionproject.core.api.plugin.TorConstants.HELPER_TRANSPORTS_USE;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_CUSTOM_BRIDGES;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_DEBUG_COUNTRY;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_HELPER_TRANSPORTS;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_HELPER_TRANSPORTS_COUNTRY;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK_AUTOMATIC;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK_WITHOUT_BRIDGES;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK_WITH_BRIDGES;

@Singleton
@NotNullByDefault
public class TorBridgeConfigurator implements EventListener {

	private final SettingsManager settingsManager;
	private final CircumventionProvider circumventionProvider;
	private final LocationUtils locationUtils;
	private final TorWrapper tor;
	private final EventBus eventBus;
	private final Executor ioExecutor;
	private final FeatureFlags featureFlags;
	private final TaskScheduler scheduler;
	private final Clock clock;
	private final BridgeListStore bridgeList;
	private final BridgeHealth health;

	public static final String ANY_COUNTRY = "*";
	static final long STALL_CHECK_MS = MINUTES.toMillis(5);
	static final long REPROMPT_INTERVAL_MS = MINUTES.toMillis(30);
	static final long RETRY_CHECK_MS = MINUTES.toMillis(30);
	private static final String BRIDGE_PREFIX = "Bridge ";

	@Nullable
	private volatile String helperPrompt = null;

	@Nullable
	private volatile String lastCountry = null;

	private volatile boolean lastCountryNeedsHelpers = false;

	private volatile int bootstrap = 0;

	@Nullable
	private String declinedCountry = null;

	@Nullable
	private Cancellable stallCheck = null;

	private long lastReprompt = 0;

	@Nullable
	private Cancellable retryCheck = null;

	private List<String> lastManaged = Collections.emptyList();

	private List<String> lastChosen = Collections.emptyList();

	private volatile Set<String> handedFingerprints = Collections.emptySet();

	private final AtomicLong generation = new AtomicLong(0);
	private volatile long applyGeneration = 0;
	private volatile long stopGeneration = 0;

	TorBridgeConfigurator(SettingsManager settingsManager,
			CircumventionProvider circumventionProvider,
			LocationUtils locationUtils, TorWrapper tor, EventBus eventBus,
			Executor ioExecutor, FeatureFlags featureFlags,
			TaskScheduler scheduler, Clock clock) {
		this(settingsManager, circumventionProvider, locationUtils, tor,
				eventBus, ioExecutor, featureFlags, scheduler, clock,
				new BridgeListStore(settingsManager, clock,
						BridgeListKeys.pinnedOnly(), () -> ""),
				new BridgeHealth(settingsManager, clock, () -> ""));
	}

	@Inject
	public TorBridgeConfigurator(SettingsManager settingsManager,
			CircumventionProvider circumventionProvider,
			LocationUtils locationUtils, TorWrapper tor, EventBus eventBus,
			@IoExecutor Executor ioExecutor, FeatureFlags featureFlags,
			TaskScheduler scheduler, Clock clock, BridgeListStore bridgeList,
			BridgeHealth health) {
		this.settingsManager = settingsManager;
		this.circumventionProvider = circumventionProvider;
		this.locationUtils = locationUtils;
		this.tor = tor;
		this.eventBus = eventBus;
		this.ioExecutor = ioExecutor;
		this.featureFlags = featureFlags;
		this.scheduler = scheduler;
		this.clock = clock;
		this.bridgeList = bridgeList;
		this.health = health;
		eventBus.addListener(this);
	}

	@Nullable
	public String pendingHelperPrompt() {
		return helperPrompt;
	}

	public void clearHelperPrompt() {
		helperPrompt = null;
	}

	@Nullable
	public String lastCountry() {
		return lastCountry;
	}

	public String helperChoiceCountry() {
		String country = lastCountry;
		return country == null || !lastCountryNeedsHelpers
				? ANY_COUNTRY : country;
	}

	public static boolean needsHelperTransports(List<BridgeType> types) {
		return types.contains(BridgeType.SNOWFLAKE)
				|| types.contains(BridgeType.MEEK);
	}

	public static boolean helperChoiceApplies(int choice,
			@Nullable String choiceCountry, String country) {
		return choice != HELPER_TRANSPORTS_ASK
				&& (ANY_COUNTRY.equals(choiceCountry)
				|| country.equals(choiceCountry));
	}

	private synchronized void noteDeclined(String country) {
		if (country.equals(declinedCountry)) return;
		cancelStallCheck();
		declinedCountry = country;
		stallCheck = scheduler.scheduleWithFixedDelay(this::checkStall,
				ioExecutor, STALL_CHECK_MS, STALL_CHECK_MS, MILLISECONDS);
	}

	private synchronized void clearDeclined() {
		declinedCountry = null;
		cancelStallCheck();
	}

	private void cancelStallCheck() {
		Cancellable c = stallCheck;
		stallCheck = null;
		if (c != null) c.cancel();
	}

	private void checkStall() {
		String country;
		synchronized (this) {
			country = declinedCountry;
			if (country == null || bootstrap >= 100) return;
			long now = clock.currentTimeMillis();
			if (now - lastReprompt < REPROMPT_INTERVAL_MS) return;
			lastReprompt = now;
		}
		helperPrompt = country;
		eventBus.broadcast(new TorHelperTransportsPromptEvent(country));
	}

	public static List<BridgeType> withHelperConsent(List<BridgeType> types,
			int choice, @Nullable String choiceCountry, String country) {
		if (choice == HELPER_TRANSPORTS_USE
				&& helperChoiceApplies(choice, choiceCountry, country)) {
			return types;
		}
		List<BridgeType> out = new ArrayList<>();
		for (BridgeType t : types) {
			if (t != BridgeType.SNOWFLAKE && t != BridgeType.MEEK) out.add(t);
		}
		return out;
	}

	private String resolveCountry(Settings s) {
		String country = locationUtils.getCurrentCountry();
		if (featureFlags.shouldEnableDebugCountryOverride()) {
			String override = s.get(PREF_TOR_DEBUG_COUNTRY);
			if (override != null && override.trim().matches("[A-Za-z]{2}")) {
				country = override.trim().toUpperCase(US);
			}
		}
		lastCountry = country;
		return country;
	}

	private void setHelperPrompt(@Nullable String country) {
		String before = helperPrompt;
		helperPrompt = country;
		if (country != null && !country.equals(before)) {
			eventBus.broadcast(new TorHelperTransportsPromptEvent(country));
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof SettingsUpdatedEvent) {
			SettingsUpdatedEvent s = (SettingsUpdatedEvent) e;
			if (TorConstants.ID.getString().equals(s.getNamespace())) {
				ioExecutor.execute(this::applyOrDisableNetwork);
			}
		} else if (e instanceof TorBootstrapEvent) {
			bootstrap = ((TorBootstrapEvent) e).getPercentage();
		} else if (e instanceof TransportStateEvent) {
			TransportStateEvent t = (TransportStateEvent) e;
			if (TorConstants.ID.equals(t.getTransportId())
					&& t.getState() == STARTING_STOPPING
					&& applyGeneration <= stopGeneration) {
				torStopped();
			}
		}
	}

	public void torStopped() {
		stopGeneration = generation.incrementAndGet();
		bootstrap = 0;
		clearDeclined();
		noteManaged(Collections.<String>emptyList(),
				Collections.<String>emptyList());
	}

	public void onRelayConnection(String fingerprint, boolean connected) {
		if (!handedFingerprints.contains(fingerprint)) return;
		ioExecutor.execute(() -> health.record(fingerprint, connected));
	}

	synchronized boolean retryChecksRunning() {
		return retryCheck != null;
	}

	private synchronized void noteManaged(List<String> managed,
			List<String> chosen) {
		lastManaged = managed;
		lastChosen = chosen;
		Set<String> fingerprints = new HashSet<>();
		for (String line : chosen) {
			String fp = BridgeLines.fingerprint(line);
			if (fp != null) fingerprints.add(fp);
		}
		handedFingerprints = Collections.unmodifiableSet(fingerprints);
		if (chosen.size() < managed.size()) {
			if (retryCheck == null) {
				retryCheck = scheduler.scheduleWithFixedDelay(this::checkRetry,
						ioExecutor, RETRY_CHECK_MS, RETRY_CHECK_MS,
						MILLISECONDS);
			}
		} else {
			Cancellable c = retryCheck;
			retryCheck = null;
			if (c != null) c.cancel();
		}
	}

	private void checkRetry() {
		List<String> managed;
		List<String> chosen;
		synchronized (this) {
			if (retryCheck == null) return;
			managed = lastManaged;
			chosen = lastChosen;
		}
		if (bootstrap >= 100) return;
		if (!health.select(managed).equals(chosen)) applyOrDisableNetwork();
	}

	static List<String> withRemoteLines(BridgeType type, List<String> bundled,
			@Nullable BridgeList remote) {
		List<String> lines = remote == null
				? Collections.<String>emptyList() : remote.lines(type);
		if (lines.isEmpty()) return bundled;
		List<String> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String line : lines) {
			out.add(BRIDGE_PREFIX + line);
			seen.add(BridgeLines.fingerprint(line));
		}
		for (String line : bundled) {
			if (out.size() >= BridgeHealth.SAFE_MINIMUM) break;
			String fp = BridgeLines.fingerprint(line);
			if (fp != null && seen.add(fp)) out.add(line);
		}
		return out;
	}

	private static List<String> withoutRepeats(List<String> lines) {
		List<String> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String line : lines) {
			String fp = BridgeLines.fingerprint(line);
			if (seen.add(fp == null ? line : fp)) out.add(line);
		}
		return out;
	}

	synchronized boolean stallChecksRunning() {
		return stallCheck != null;
	}

	@Nullable
	private volatile Runnable appliedListener;

	public void setAppliedListener(@Nullable Runnable listener) {
		appliedListener = listener;
	}

	private void applyOrDisableNetwork() {
		if (!apply()) {
			try {
				tor.enableNetwork(false);
			} catch (IOException e) {
			}
		}
		Runnable listener = appliedListener;
		if (listener == null) return;
		try {
			listener.run();
		} catch (RuntimeException ignored) {
		}
	}

	private volatile boolean lastApplyFailed = false;
	private volatile boolean usingBridges = false;

	public boolean isUsingBridges() {
		return usingBridges;
	}

	static boolean usesBridges(int network, boolean countryNeedsBridges) {
		return network == PREF_TOR_NETWORK_WITH_BRIDGES
				|| (network == PREF_TOR_NETWORK_AUTOMATIC
						&& countryNeedsBridges);
	}

	public boolean apply() {
		applyGeneration = generation.incrementAndGet();
		Settings s;
		try {
			s = settingsManager.getSettings(TorConstants.ID.getString());
		} catch (DbException e) {
			return false;
		}
		int network = s.getInt(PREF_TOR_NETWORK, DEFAULT_PREF_TOR_NETWORK);
		if (network != PREF_TOR_NETWORK_AUTOMATIC
				&& network != PREF_TOR_NETWORK_WITHOUT_BRIDGES
				&& network != PREF_TOR_NETWORK_WITH_BRIDGES) {
			return false;
		}
		String country = resolveCountry(s);
		boolean useBridges = usesBridges(network,
				circumventionProvider.shouldUseBridges(country));
		usingBridges = useBridges;
		lastCountryNeedsHelpers = false;
		if (!useBridges) {
			setHelperPrompt(null);
			clearDeclined();
			noteManaged(Collections.<String>emptyList(),
					Collections.<String>emptyList());
			bridgeList.noteBuiltInBridges(false);
			health.maintain();
			try {
				tor.disableBridges();
			} catch (IOException e) {
			}
			return true;
		}
		List<String> bridges;
		String custom = s.get(PREF_TOR_CUSTOM_BRIDGES);
		if (custom != null && !custom.trim().isEmpty()) {
			setHelperPrompt(null);
			clearDeclined();
			noteManaged(Collections.<String>emptyList(),
					Collections.<String>emptyList());
			bridgeList.noteBuiltInBridges(false);
			health.maintain();
			bridges = parseCustomBridges(custom);
		} else {
			bridges = new ArrayList<>();
			List<BridgeType> types =
					circumventionProvider.getSuitableBridgeTypes(country);
			int choice = s.getInt(PREF_TOR_HELPER_TRANSPORTS,
					HELPER_TRANSPORTS_ASK);
			String choiceCountry = s.get(PREF_TOR_HELPER_TRANSPORTS_COUNTRY);
			boolean needs = needsHelperTransports(types);
			boolean applies =
					helperChoiceApplies(choice, choiceCountry, country);
			lastCountryNeedsHelpers = needs;
			setHelperPrompt(needs && !applies ? country : null);
			types = withHelperConsent(types, choice, choiceCountry, country);
			if (needs && applies && choice == HELPER_TRANSPORTS_OFF) {
				noteDeclined(country);
			} else {
				clearDeclined();
			}
			BridgeList remote = bridgeList.current();
			List<String> managed = new ArrayList<>();
			List<String> bundledManaged = new ArrayList<>();
			List<String> helpers = new ArrayList<>();
			for (BridgeType type : types) {
				List<String> bundled =
						circumventionProvider.getBridges(type, country);
				if (type == BridgeType.SNOWFLAKE || type == BridgeType.MEEK) {
					helpers.addAll(bundled);
				} else {
					managed.addAll(withRemoteLines(type, bundled, remote));
					bundledManaged.addAll(bundled);
				}
			}
			managed = withoutRepeats(managed);
			if (remote != null && health.allFailing(managed)) {
				managed.addAll(bundledManaged);
				managed = withoutRepeats(managed);
			}
			List<String> chosen = health.select(managed);
			noteManaged(managed, chosen);
			bridgeList.noteBuiltInBridges(!managed.isEmpty());
			bridges.addAll(chosen);
			bridges.addAll(helpers);
		}
		if (bridges.isEmpty()) {
			lastApplyFailed = true;
			return false;
		}
		try {
			if (lastApplyFailed) {
				try {
					tor.disableBridges();
				} catch (IOException ignored) {
				}
			}
			tor.enableBridges(bridges);
			lastApplyFailed = false;
			return true;
		} catch (IOException e) {
			lastApplyFailed = true;
			return false;
		}
	}

	public static boolean isPlausibleBridgeLine(String line) {
		String t = line.trim();
		if (t.isEmpty() || t.length() > 512) return false;
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			if (c < 0x20 || c == 0x7F || c == '"' || c == '\\') return false;
		}
		String[] tokens = t.split(" +");
		int i = looksLikeAddress(tokens[0]) ? 0 : 1;
		if (i == 1 && !tokens[0].matches("[A-Za-z0-9_]+")) return false;
		if (i >= tokens.length) return false;
		if (!looksLikeAddress(tokens[i])) return false;
		for (int k = i + 1; k < tokens.length; k++) {
			if (!tokens[k].matches("[A-Za-z0-9_.:=/+-]+")) return false;
		}
		return true;
	}

	private static boolean looksLikeAddress(String s) {
		int colon = s.lastIndexOf(':');
		if (colon <= 0 || colon == s.length() - 1) return false;
		String host = s.substring(0, colon);
		String port = s.substring(colon + 1);
		if (!port.matches("[0-9]{1,5}")) return false;
		int p = Integer.parseInt(port);
		if (p < 1 || p > 65535) return false;
		if (host.startsWith("[") && host.endsWith("]")) {
			return host.substring(1, host.length() - 1).matches("[0-9A-Fa-f:.]+");
		}
		return host.matches("[A-Za-z0-9.-]+");
	}

	static List<String> parseCustomBridges(String value) {
		List<String> bridges = new ArrayList<>();
		for (String line : value.split("\\r?\\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) continue;
			String lower = trimmed.toLowerCase(java.util.Locale.US);
			if (lower.startsWith("bridge ")) {
				bridges.add(trimmed);
			} else {
				bridges.add("Bridge " + trimmed);
			}
		}
		return bridges;
	}
}

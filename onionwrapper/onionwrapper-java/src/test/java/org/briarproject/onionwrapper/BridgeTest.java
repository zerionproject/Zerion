package org.briarproject.onionwrapper;

import org.briarproject.onionwrapper.CircumventionProvider.BridgeType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import javax.annotation.concurrent.GuardedBy;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.DEFAULT_OBFS4;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.MEEK;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.NON_DEFAULT_OBFS4;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.SNOWFLAKE;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.VANILLA;
import static org.briarproject.onionwrapper.CircumventionProvider.COUNTRIES_DEFAULT_OBFS4;
import static org.briarproject.onionwrapper.CircumventionProvider.COUNTRIES_MEEK;
import static org.briarproject.onionwrapper.CircumventionProvider.COUNTRIES_NON_DEFAULT_OBFS4;
import static org.briarproject.onionwrapper.CircumventionProvider.COUNTRIES_SNOWFLAKE;
import static org.briarproject.onionwrapper.CircumventionProvider.COUNTRIES_VANILLA;
import static org.briarproject.onionwrapper.TestUtils.deleteTestDirectory;
import static org.briarproject.onionwrapper.TestUtils.getArchitectureForTorBinary;
import static org.briarproject.onionwrapper.TestUtils.getTestDirectory;
import static org.briarproject.onionwrapper.TestUtils.isOptionalTestEnabled;
import static org.briarproject.onionwrapper.TorWrapper.TorState.CONNECTED;
import static org.briarproject.onionwrapper.util.OsUtils.isLinux;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

@RunWith(Parameterized.class)
public class BridgeTest extends BaseTest {

	private final static Logger LOG = getLogger(BridgeTest.class.getName());

	private static final List<BridgeType> ESSENTIAL_BRIDGE_TYPES = asList(MEEK, SNOWFLAKE);
	private static final int SOCKS_PORT = 59060;
	private static final int CONTROL_PORT = 59061;
	private final static long TIMEOUT = MINUTES.toMillis(2);
	private final static long MEEK_TIMEOUT = MINUTES.toMillis(10);
	private final static int UNREACHABLE_BRIDGES_ALLOWED = 6;
	private final static int ATTEMPTS_PER_BRIDGE = 5;

	@Parameters
	public static Iterable<Params> data() {
		// Share stats among all the test instances
		Stats stats = new Stats();
		CircumventionProvider provider = new CircumventionProviderImpl();
		List<Params> states = new ArrayList<>();
		for (int i = 0; i < ATTEMPTS_PER_BRIDGE; i++) {
			// Test all the unique bridge lines
			Set<String> bridges = new HashSet<>();
			for (BridgeType type : BridgeType.values()) {
				for (String bridge : provider.getBridges(type, "ZZ")) {
					if (bridges.add(bridge)) states.add(new Params(bridge, type, stats));
				}
			}
			for (String countryCode : COUNTRIES_DEFAULT_OBFS4) {
				for (String bridge : provider.getBridges(DEFAULT_OBFS4, countryCode)) {
					if (bridges.add(bridge)) states.add(new Params(bridge, DEFAULT_OBFS4, stats));
				}
			}
			for (String countryCode : COUNTRIES_NON_DEFAULT_OBFS4) {
				for (String bridge : provider.getBridges(NON_DEFAULT_OBFS4, countryCode)) {
					if (bridges.add(bridge)) {
						states.add(new Params(bridge, NON_DEFAULT_OBFS4, stats));
					}
				}
			}
			for (String countryCode : COUNTRIES_VANILLA) {
				for (String bridge : provider.getBridges(VANILLA, countryCode)) {
					if (bridges.add(bridge)) states.add(new Params(bridge, VANILLA, stats));
				}
			}
			for (String countryCode : COUNTRIES_MEEK) {
				for (String bridge : provider.getBridges(MEEK, countryCode)) {
					if (bridges.add(bridge)) states.add(new Params(bridge, MEEK, stats));
				}
			}
			for (String countryCode : COUNTRIES_SNOWFLAKE) {
				for (String bridge : provider.getBridges(SNOWFLAKE, countryCode)) {
					if (bridges.add(bridge)) states.add(new Params(bridge, SNOWFLAKE, stats));
				}
			}
		}
		return states;
	}

	private final ExecutorService executor = newCachedThreadPool();
	private final File torDir = getTestDirectory();
	private final Params params;

	public BridgeTest(Params params) {
		this.params = params;
	}

	@Before
	public void setUp() {
		assumeTrue(isOptionalTestEnabled(BridgeTest.class));
		assumeTrue(isLinux());
		assumeNotNull(getArchitectureForTorBinary());
	}

	@After
	public void tearDown() {
		deleteTestDirectory(torDir);
		executor.shutdown();
	}

	@Test
	public void testBridges() throws Exception {
		if (params.stats.hasSucceeded(params.bridge)) {
			LOG.info("Skipping previously successful bridge: " + params.bridge);
			return;
		}

		String architecture = requireNonNull(getArchitectureForTorBinary());
		TorWrapper tor = new UnixTorWrapper(executor, executor, architecture, torDir,
				CONTROL_PORT, SOCKS_PORT);

		LOG.warning("Testing " + params.bridge);
		try {
			tor.start();
			tor.enableBridges(singletonList(params.bridge));
			tor.enableNetwork(true);
			long start = System.currentTimeMillis();
			long timeout = params.bridgeType == MEEK ? MEEK_TIMEOUT : TIMEOUT;
			while (System.currentTimeMillis() - start < timeout) {
				if (tor.getTorState() == CONNECTED) break;
				//noinspection BusyWait
				Thread.sleep(500);
			}
			if (tor.getTorState() == CONNECTED) {
				LOG.info("Connected to Tor: " + params.bridge);
				params.stats.countSuccess(params.bridge);
			} else {
				LOG.warning("Could not connect to Tor within timeout: " + params.bridge);
				params.stats.countFailure(params.bridge, params.bridgeType);
			}
		} finally {
			tor.stop();
		}
	}

	private static class Params {

		private final String bridge;
		private final BridgeType bridgeType;
		private final Stats stats;

		private Params(String bridge, BridgeType bridgeType, Stats stats) {
			this.bridge = bridge;
			this.bridgeType = bridgeType;
			this.stats = stats;
		}
	}

	private static class Stats {

		@GuardedBy("this")
		private final Set<String> successes = new HashSet<>();
		@GuardedBy("this")
		private final Multiset<String> failures = new Multiset<>();
		@GuardedBy("this")
		private final Set<String> unreachable = new TreeSet<>();

		private synchronized boolean hasSucceeded(String bridge) {
			return successes.contains(bridge);
		}

		private synchronized void countSuccess(String bridge) {
			successes.add(bridge);
		}

		private synchronized void countFailure(String bridge, BridgeType bridgeType) {
			if (failures.add(bridge) == ATTEMPTS_PER_BRIDGE) {
				LOG.warning("Bridge is unreachable after "
						+ ATTEMPTS_PER_BRIDGE + " attempts: " + bridge);
				unreachable.add(bridge);
				if (unreachable.size() > UNREACHABLE_BRIDGES_ALLOWED) {
					fail(unreachable.size() + " bridges are unreachable: " + unreachable);
				}
				if (ESSENTIAL_BRIDGE_TYPES.contains(bridgeType)) {
					fail("essential bridge is unreachable");
				}
			}
		}
	}
}

package org.zerionproject.tor;

import org.junit.Test;
import org.zerionproject.tor.CircumventionProvider.BridgeType;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.tor.CircumventionProvider.BridgeType.DEFAULT_OBFS4;
import static org.zerionproject.tor.CircumventionProvider.BridgeType.MEEK;
import static org.zerionproject.tor.CircumventionProvider.BridgeType.NON_DEFAULT_OBFS4;
import static org.zerionproject.tor.CircumventionProvider.BridgeType.SNOWFLAKE;
import static org.zerionproject.tor.CircumventionProvider.BridgeType.VANILLA;
import static org.zerionproject.tor.CircumventionProvider.COUNTRIES_DEFAULT_OBFS4;
import static org.zerionproject.tor.CircumventionProvider.COUNTRIES_MEEK;
import static org.zerionproject.tor.CircumventionProvider.COUNTRIES_NON_DEFAULT_OBFS4;
import static org.zerionproject.tor.CircumventionProvider.COUNTRIES_SNOWFLAKE;
import static org.zerionproject.tor.CircumventionProvider.COUNTRIES_VANILLA;

public class CircumventionProviderImplTest {

	private final CircumventionProviderImpl provider =
			new CircumventionProviderImpl();

	@Test
	public void testShouldUseBridges() {
		for (String c : COUNTRIES_DEFAULT_OBFS4) {
			assertTrue(provider.shouldUseBridges(c));
		}
		for (String c : COUNTRIES_NON_DEFAULT_OBFS4) {
			assertTrue(provider.shouldUseBridges(c));
		}
		for (String c : COUNTRIES_VANILLA) assertTrue(provider.shouldUseBridges(c));
		for (String c : COUNTRIES_MEEK) assertTrue(provider.shouldUseBridges(c));
		for (String c : COUNTRIES_SNOWFLAKE) {
			assertTrue(provider.shouldUseBridges(c));
		}
		assertFalse(provider.shouldUseBridges("US"));
		assertFalse(provider.shouldUseBridges("ZZ"));
	}

	@Test
	public void testGetSuitableBridgeTypes() {
		for (String c : COUNTRIES_DEFAULT_OBFS4) suitableAndExist(DEFAULT_OBFS4, c);
		for (String c : COUNTRIES_NON_DEFAULT_OBFS4) {
			suitableAndExist(NON_DEFAULT_OBFS4, c);
		}
		for (String c : COUNTRIES_VANILLA) suitableAndExist(VANILLA, c);
		for (String c : COUNTRIES_MEEK) suitableAndExist(MEEK, c);
		for (String c : COUNTRIES_SNOWFLAKE) suitableAndExist(SNOWFLAKE, c);
		suitableAndExist(DEFAULT_OBFS4, "US");
		suitableAndExist(VANILLA, "US");
	}

	@Test
	public void everyBridgeLineIsABridgeLine() {
		for (BridgeType type : BridgeType.values()) {
			for (String c : new String[] {"US", "BY", "CN", "IR", "RU", "TM"}) {
				List<String> bridges = provider.getBridges(type, c);
				assertFalse(type + " " + c, bridges.isEmpty());
				for (String b : bridges) {
					assertTrue(b, b.startsWith("Bridge "));
					assertTrue(b, b.length() > "Bridge ".length());
					assertFalse(b, b.contains("\n"));
				}
			}
		}
	}

	private void suitableAndExist(BridgeType type, String countryCode) {
		assertTrue(provider.getSuitableBridgeTypes(countryCode).contains(type));
		assertFalse(provider.getBridges(type, countryCode).isEmpty());
	}
}

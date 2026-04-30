package org.briarproject.onionwrapper;

import org.briarproject.onionwrapper.CircumventionProvider.BridgeType;
import org.junit.Test;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CircumventionProviderImplTest extends BaseTest {

	private final CircumventionProviderImpl provider =
			new CircumventionProviderImpl();

	@Test
	public void testShouldUseBridges() {
		for (String countryCode : COUNTRIES_DEFAULT_OBFS4) {
			assertTrue(provider.shouldUseBridges(countryCode));
		}
		for (String countryCode : COUNTRIES_NON_DEFAULT_OBFS4) {
			assertTrue(provider.shouldUseBridges(countryCode));
		}
		for (String countryCode : COUNTRIES_VANILLA) {
			assertTrue(provider.shouldUseBridges(countryCode));
		}
		for (String countryCode : COUNTRIES_MEEK) {
			assertTrue(provider.shouldUseBridges(countryCode));
		}
		for (String countryCode : COUNTRIES_SNOWFLAKE) {
			assertTrue(provider.shouldUseBridges(countryCode));
		}
		assertFalse(provider.shouldUseBridges("US"));
		assertFalse(provider.shouldUseBridges("ZZ"));
	}

	@Test
	public void testGetSuitableBridgeTypes() {
		for (String countryCode : COUNTRIES_DEFAULT_OBFS4) {
			testBridgesAreSuitableAndExist(DEFAULT_OBFS4, countryCode);
		}
		for (String countryCode : COUNTRIES_NON_DEFAULT_OBFS4) {
			testBridgesAreSuitableAndExist(NON_DEFAULT_OBFS4, countryCode);
		}
		for (String countryCode : COUNTRIES_VANILLA) {
			testBridgesAreSuitableAndExist(VANILLA, countryCode);
		}
		for (String countryCode : COUNTRIES_MEEK) {
			testBridgesAreSuitableAndExist(MEEK, countryCode);
		}
		for (String countryCode : COUNTRIES_SNOWFLAKE) {
			testBridgesAreSuitableAndExist(SNOWFLAKE, countryCode);
		}
		// If bridges are enabled manually in a country with no specific bridge recommendations,
		// we should use default obfs4 and vanilla
		testBridgesAreSuitableAndExist(DEFAULT_OBFS4, "US");
		testBridgesAreSuitableAndExist(VANILLA, "US");
	}

	@Test
	public void testIPv6BridgeTypes() {
		// If we're on an IPv6-only network we'll use meek and snowflake in any country
		assertFalse(provider.getBridges(MEEK, "US").isEmpty());
		assertFalse(provider.getBridges(SNOWFLAKE, "US").isEmpty());
	}

	private void testBridgesAreSuitableAndExist(BridgeType type, String countryCode) {
		assertTrue(provider.getSuitableBridgeTypes(countryCode).contains(type));
		assertFalse(provider.getBridges(type, countryCode).isEmpty());
	}
}

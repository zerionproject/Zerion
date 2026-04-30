package org.briarproject.onionwrapper;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static java.util.Arrays.asList;
import static java.util.Locale.US;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.DEFAULT_OBFS4;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.MEEK;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.NON_DEFAULT_OBFS4;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.SNOWFLAKE;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.VANILLA;

@Immutable
@NotNullByDefault
class CircumventionProviderImpl implements CircumventionProvider {

	private static final String DEFAULT_COUNTRY_CODE = "ZZ";

	private static final Set<String> USE_DEFAULT_OBFS4 =
			new HashSet<>(asList(COUNTRIES_DEFAULT_OBFS4));
	private static final Set<String> USE_NON_DEFAULT_OBFS4 =
			new HashSet<>(asList(COUNTRIES_NON_DEFAULT_OBFS4));
	private static final Set<String> USE_VANILLA = new HashSet<>(asList(COUNTRIES_VANILLA));
	private static final Set<String> USE_MEEK = new HashSet<>(asList(COUNTRIES_MEEK));
	private static final Set<String> USE_SNOWFLAKE = new HashSet<>(asList(COUNTRIES_SNOWFLAKE));

	@Inject
	CircumventionProviderImpl() {
	}

	@Override
	public boolean shouldUseBridges(String countryCode) {
		return USE_DEFAULT_OBFS4.contains(countryCode) ||
				USE_NON_DEFAULT_OBFS4.contains(countryCode) ||
				USE_VANILLA.contains(countryCode) ||
				USE_MEEK.contains(countryCode) ||
				USE_SNOWFLAKE.contains(countryCode);
	}

	@Override
	public List<BridgeType> getSuitableBridgeTypes(String countryCode) {
		List<BridgeType> types = new ArrayList<>();
		if (USE_DEFAULT_OBFS4.contains(countryCode)) types.add(DEFAULT_OBFS4);
		if (USE_NON_DEFAULT_OBFS4.contains(countryCode)) types.add(NON_DEFAULT_OBFS4);
		if (USE_VANILLA.contains(countryCode)) types.add(VANILLA);
		if (USE_MEEK.contains(countryCode)) types.add(MEEK);
		if (USE_SNOWFLAKE.contains(countryCode)) types.add(SNOWFLAKE);
		// If we don't have any recommendations for this country then use the defaults
		if (types.isEmpty()) {
			types.add(DEFAULT_OBFS4);
			types.add(VANILLA);
		}
		return types;
	}

	@Override
	public List<String> getBridges(BridgeType type, String countryCode) {
		ClassLoader cl = getClass().getClassLoader();
		// Try to load bridges that are specific to this country code
		String filename = makeResourceFilename(type, countryCode);
		InputStream is = cl.getResourceAsStream(filename);
		if (is == null) {
			// No resource for this country code - use the fallback resource
			filename = makeResourceFilename(type, DEFAULT_COUNTRY_CODE);
			is = requireNonNull(cl.getResourceAsStream(filename));
		}
		List<String> bridges = new ArrayList<>();
		Scanner scanner = new Scanner(is);
		while (scanner.hasNextLine()) {
			bridges.add("Bridge " + scanner.nextLine());
		}
		scanner.close();
		return bridges;
	}

	private String makeResourceFilename(BridgeType type, String countryCode) {
		return "bridges-" + type.letter + "-" + countryCode.toLowerCase(US);
	}
}

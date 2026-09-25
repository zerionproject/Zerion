package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import javax.annotation.concurrent.Immutable;

import static java.util.Arrays.asList;
import static java.util.Locale.US;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;
import static org.zerionproject.tor.CircumventionProvider.BridgeType.DEFAULT_OBFS4;
import static org.zerionproject.tor.CircumventionProvider.BridgeType.MEEK;
import static org.zerionproject.tor.CircumventionProvider.BridgeType.NON_DEFAULT_OBFS4;
import static org.zerionproject.tor.CircumventionProvider.BridgeType.SNOWFLAKE;
import static org.zerionproject.tor.CircumventionProvider.BridgeType.VANILLA;

/**
 * Bridge lines are read from the {@code bridges-<type>-<country>} resources
 * on the class path, falling back to the {@code zz} list of a type when
 * there is no country-specific one.
 */
@Immutable
@NotNullByDefault
class CircumventionProviderImpl implements CircumventionProvider {

	private static final String DEFAULT_COUNTRY_CODE = "ZZ";

	private static final Set<String> USE_DEFAULT_OBFS4 =
			new HashSet<>(asList(COUNTRIES_DEFAULT_OBFS4));
	private static final Set<String> USE_NON_DEFAULT_OBFS4 =
			new HashSet<>(asList(COUNTRIES_NON_DEFAULT_OBFS4));
	private static final Set<String> USE_VANILLA =
			new HashSet<>(asList(COUNTRIES_VANILLA));
	private static final Set<String> USE_MEEK =
			new HashSet<>(asList(COUNTRIES_MEEK));
	private static final Set<String> USE_SNOWFLAKE =
			new HashSet<>(asList(COUNTRIES_SNOWFLAKE));

	CircumventionProviderImpl() {
	}

	@Override
	public boolean shouldUseBridges(String countryCode) {
		return USE_DEFAULT_OBFS4.contains(countryCode)
				|| USE_NON_DEFAULT_OBFS4.contains(countryCode)
				|| USE_VANILLA.contains(countryCode)
				|| USE_MEEK.contains(countryCode)
				|| USE_SNOWFLAKE.contains(countryCode);
	}

	@Override
	public List<BridgeType> getSuitableBridgeTypes(String countryCode) {
		List<BridgeType> types = new ArrayList<>();
		if (USE_DEFAULT_OBFS4.contains(countryCode)) types.add(DEFAULT_OBFS4);
		if (USE_NON_DEFAULT_OBFS4.contains(countryCode)) {
			types.add(NON_DEFAULT_OBFS4);
		}
		if (USE_VANILLA.contains(countryCode)) types.add(VANILLA);
		if (USE_MEEK.contains(countryCode)) types.add(MEEK);
		if (USE_SNOWFLAKE.contains(countryCode)) types.add(SNOWFLAKE);
		if (types.isEmpty()) {
			types.add(DEFAULT_OBFS4);
			types.add(VANILLA);
		}
		return types;
	}

	@Override
	public List<String> getBridges(BridgeType type, String countryCode) {
		ClassLoader cl = getClass().getClassLoader();
		String filename = makeResourceFilename(type, countryCode);
		InputStream is = cl.getResourceAsStream(filename);
		if (is == null) {
			filename = makeResourceFilename(type, DEFAULT_COUNTRY_CODE);
			is = requireNonNull(cl.getResourceAsStream(filename));
		}
		List<String> bridges = new ArrayList<>();
		try (Scanner scanner = new Scanner(is, "UTF-8")) {
			while (scanner.hasNextLine()) {
				String line = scanner.nextLine().trim();
				if (!line.isEmpty()) bridges.add("Bridge " + line);
			}
		}
		return bridges;
	}

	private String makeResourceFilename(BridgeType type, String countryCode) {
		return "bridges-" + type.letter + "-" + countryCode.toLowerCase(US);
	}
}

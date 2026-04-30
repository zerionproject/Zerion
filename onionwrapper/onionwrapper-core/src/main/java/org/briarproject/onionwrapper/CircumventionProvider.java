package org.briarproject.onionwrapper;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public interface CircumventionProvider {

	enum BridgeType {

		DEFAULT_OBFS4("d"),
		NON_DEFAULT_OBFS4("n"),
		VANILLA("v"),
		MEEK("m"),
		SNOWFLAKE("s");

		final String letter;

		BridgeType(String letter) {
			this.letter = letter;
		}
	}

	/**
	 * Countries where default obfs4 bridges should be used.
	 */
	String[] COUNTRIES_DEFAULT_OBFS4 = {"BY"};

	/**
	 * Countries where non-default obfs4 bridges should be used.
	 */
	String[] COUNTRIES_NON_DEFAULT_OBFS4 = {"BY", "CN", "EG", "HK", "IR", "MM", "RU", "TM"};

	/**
	 * Countries where vanilla bridges should be used.
	 */
	String[] COUNTRIES_VANILLA = {"BY"};

	/**
	 * Countries where meek bridges should be used.
	 */
	String[] COUNTRIES_MEEK = {"TM"};

	/**
	 * Countries where snowflake bridges should be used.
	 */
	String[] COUNTRIES_SNOWFLAKE = {"BY", "CN", "EG", "HK", "IR", "MM", "RU", "TM"};

	/**
	 * Returns true if bridges should be used by default in the given country.
	 */
	boolean shouldUseBridges(String countryCode);

	/**
	 * Returns the types of bridge connection that are suitable for the given country, or
	 * {@link BridgeType#DEFAULT_OBFS4} and {@link BridgeType#VANILLA} if we don't have any
	 * specific recommendations for the given country.
	 */
	List<BridgeType> getSuitableBridgeTypes(String countryCode);

	/**
	 * Returns bridges of the given type that are usable in the given country.
	 */
	List<String> getBridges(BridgeType type, String countryCode);
}

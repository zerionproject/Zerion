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

	String[] COUNTRIES_DEFAULT_OBFS4 = {"BY"};

	String[] COUNTRIES_NON_DEFAULT_OBFS4 = {"BY", "CN", "EG", "HK", "IR", "MM", "RU", "TM"};

	String[] COUNTRIES_VANILLA = {"BY"};

	String[] COUNTRIES_MEEK = {"TM"};

	String[] COUNTRIES_SNOWFLAKE = {"BY", "CN", "EG", "HK", "IR", "MM", "RU", "TM"};

	boolean shouldUseBridges(String countryCode);

	List<BridgeType> getSuitableBridgeTypes(String countryCode);

	List<String> getBridges(BridgeType type, String countryCode);
}

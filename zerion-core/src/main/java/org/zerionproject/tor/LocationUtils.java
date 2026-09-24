package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

@NotNullByDefault
public interface LocationUtils {

	/**
	 * The country the device is currently in as an upper-case ISO 3166-1
	 * alpha-2 code, or the empty string if it cannot be determined.
	 */
	String getCurrentCountry();

	/**
	 * The display name of the country with the given code, or the code
	 * itself if no name is known.
	 */
	static String getCountryDisplayName(String isoCode) {
		for (Locale locale : Locale.getAvailableLocales()) {
			if (locale.getCountry().equalsIgnoreCase(isoCode)) {
				return locale.getDisplayCountry();
			}
		}
		return isoCode;
	}
}

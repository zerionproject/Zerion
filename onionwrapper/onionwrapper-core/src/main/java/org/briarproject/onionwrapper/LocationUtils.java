package org.briarproject.onionwrapper;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

@NotNullByDefault
public interface LocationUtils {

	/**
	 * Get the country the device is currently located in, or "" if it cannot
	 * be determined.
	 * <p>
	 * The country codes are formatted upper-case and as per <a href="
	 * https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2">ISO 3166-1 alpha 2</a>.
	 */
	String getCurrentCountry();

	/**
	 * Returns the name of the country for display in the UI
	 * or the isoCode if none could be found.
	 *
	 * @param isoCode The result from {@link #getCurrentCountry()}.
	 */
	static String getCountryDisplayName(String isoCode) {
		for (Locale locale : Locale.getAvailableLocales()) {
			if (locale.getCountry().equalsIgnoreCase(isoCode)) {
				return locale.getDisplayCountry();
			}
		}
		// Name is unknown
		return isoCode;
	}
}

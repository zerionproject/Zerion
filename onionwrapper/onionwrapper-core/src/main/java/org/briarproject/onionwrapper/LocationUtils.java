package org.briarproject.onionwrapper;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

@NotNullByDefault
public interface LocationUtils {

	String getCurrentCountry();

	static String getCountryDisplayName(String isoCode) {
		for (Locale locale : Locale.getAvailableLocales()) {
			if (locale.getCountry().equalsIgnoreCase(isoCode)) {
				return locale.getDisplayCountry();
			}
		}

		return isoCode;
	}
}

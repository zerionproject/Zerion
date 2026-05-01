package org.briarproject.onionwrapper.util;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class StringUtils {

	// see https://stackoverflow.com/a/38947571
	static boolean startsWithIgnoreCase(String s, String prefix) {
		return s.regionMatches(true, 0, prefix, 0, prefix.length());
	}
}

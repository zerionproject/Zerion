package org.briarproject.onionwrapper.util;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class StringUtils {

	static boolean startsWithIgnoreCase(String s, String prefix) {
		return s.regionMatches(true, 0, prefix, 0, prefix.length());
	}
}

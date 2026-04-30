package org.briarproject.onionwrapper;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class JavaLocationUtilsFactory {

	public static LocationUtils createJavaLocationUtils() {
		return new JavaLocationUtils();
	}

}

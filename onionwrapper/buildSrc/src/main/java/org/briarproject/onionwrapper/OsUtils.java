package org.briarproject.onionwrapper;

import org.briarproject.nullsafety.NotNullByDefault;

import static org.briarproject.onionwrapper.StringUtils.startsWithIgnoreCase;

@NotNullByDefault
public class OsUtils {

	public static final OS currentOS;

	static {
		String os = System.getProperty("os.name");
		if (os.equalsIgnoreCase("Mac OS X")) {
			currentOS = OS.MacOS;
		} else if (startsWithIgnoreCase(os, "Win")) {
			currentOS = OS.Windows;
		} else if (startsWithIgnoreCase(os, "Linux")) {
			currentOS = OS.Linux;
		} else {
			throw new AssertionError("Unknown OS name: " + os);
		}
	}
}

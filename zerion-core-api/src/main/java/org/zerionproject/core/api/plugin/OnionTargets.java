package org.zerionproject.core.api.plugin;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@NotNullByDefault
public final class OnionTargets {

	public static final int MAX_UNIX_PATH_BYTES = 107;

	private static final String UNIX_PREFIX = "unix:";
	private static final Pattern UNIX_PATH =
			Pattern.compile("^/[A-Za-z0-9._/-]+$");
	private static final Pattern LOOPBACK =
			Pattern.compile("^127\\.0\\.0\\.1:([0-9]{1,5})$");

	private OnionTargets() {
	}

	public static String unix(File path) {
		return unixPath(path.getAbsolutePath());
	}

	public static String unixPath(String absolutePath) {
		if (!isUnixPath(absolutePath)) {
			throw new IllegalArgumentException("socket path");
		}
		return UNIX_PREFIX + absolutePath;
	}

	public static String loopback(int port) {
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("port");
		}
		return "127.0.0.1:" + port;
	}

	public static boolean isValid(String target) {
		return isUnix(target) || loopbackPort(target) > 0;
	}

	public static boolean isUnix(String target) {
		return target.startsWith(UNIX_PREFIX)
				&& isUnixPath(target.substring(UNIX_PREFIX.length()));
	}

	public static int loopbackPort(String target) {
		Matcher m = LOOPBACK.matcher(target);
		if (!m.matches()) return -1;
		int port = Integer.parseInt(m.group(1));
		return port >= 1 && port <= 65535 ? port : -1;
	}

	private static boolean isUnixPath(String path) {
		if (!UNIX_PATH.matcher(path).matches()) return false;
		if (path.contains("/../") || path.endsWith("/..")) return false;
		return path.length() <= MAX_UNIX_PATH_BYTES;
	}
}

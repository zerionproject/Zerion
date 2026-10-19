package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@NotNullByDefault
public final class BridgeLines {

	public static final int MAX_LINE_LENGTH = 512;

	private static final String PREFIX = "Bridge ";

	private static final Pattern TOKEN = Pattern.compile("[!-~]+");
	private static final Pattern FINGERPRINT = Pattern.compile("[0-9A-F]{40}");
	private static final Pattern CERT = Pattern.compile("cert=[A-Za-z0-9+/]{70}");
	private static final Pattern IAT_MODE = Pattern.compile("iat-mode=[012]");
	private static final Pattern IPV4_PORT = Pattern.compile(
			"(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})"
					+ "\\.(0|[1-9][0-9]{0,2}):([1-9][0-9]{0,4})");

	private static final int[][] NOT_PUBLIC = {
			{0, 0, 0, 0, 8},
			{10, 0, 0, 0, 8},
			{100, 64, 0, 0, 10},
			{127, 0, 0, 0, 8},
			{169, 254, 0, 0, 16},
			{172, 16, 0, 0, 12},
			{192, 0, 0, 0, 24},
			{192, 0, 2, 0, 24},
			{192, 88, 99, 0, 24},
			{192, 168, 0, 0, 16},
			{198, 18, 0, 0, 15},
			{198, 51, 100, 0, 24},
			{203, 0, 113, 0, 24},
			{224, 0, 0, 0, 4},
			{240, 0, 0, 0, 4}
	};

	private BridgeLines() {
	}

	public static boolean isWellFormed(String line) {
		return tokens(line) != null;
	}

	public static boolean isObfs4Line(String line) {
		String[] t = tokens(line);
		return t != null && t.length == 5 && t[0].equals("obfs4")
				&& isPublicIpv4Address(t[1])
				&& FINGERPRINT.matcher(t[2]).matches()
				&& CERT.matcher(t[3]).matches()
				&& IAT_MODE.matcher(t[4]).matches();
	}

	public static boolean isVanillaLine(String line) {
		String[] t = tokens(line);
		return t != null && t.length == 2 && isPublicIpv4Address(t[0])
				&& FINGERPRINT.matcher(t[1]).matches();
	}

	@Nullable
	public static String fingerprint(String line) {
		String bare = line.startsWith(PREFIX)
				? line.substring(PREFIX.length()) : line;
		if (isObfs4Line(bare)) return bare.split(" ")[2];
		if (isVanillaLine(bare)) return bare.split(" ")[1];
		return null;
	}

	@Nullable
	public static String relayFingerprint(String orName) {
		if (orName.length() < 41 || orName.charAt(0) != '$') return null;
		if (orName.length() > 41) {
			char c = orName.charAt(41);
			if (c != '~' && c != '=') return null;
		}
		String fp = orName.substring(1, 41);
		for (int i = 0; i < fp.length(); i++) {
			char c = fp.charAt(i);
			boolean hex = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')
					|| (c >= 'a' && c <= 'f');
			if (!hex) return null;
		}
		return fp.toUpperCase(Locale.US);
	}

	static boolean isPublicIpv4Address(String addressAndPort) {
		Matcher m = IPV4_PORT.matcher(addressAndPort);
		if (!m.matches()) return false;
		int[] octets = new int[4];
		for (int i = 0; i < 4; i++) {
			octets[i] = Integer.parseInt(m.group(i + 1));
			if (octets[i] > 255) return false;
		}
		int port = Integer.parseInt(m.group(5));
		if (port > 65535) return false;
		long address = 0;
		for (int o : octets) address = (address << 8) | o;
		for (int[] range : NOT_PUBLIC) {
			long base = ((long) range[0] << 24) | (range[1] << 16)
					| (range[2] << 8) | range[3];
			int bits = range[4];
			long mask = (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
			if ((address & mask) == (base & mask)) return false;
		}
		return true;
	}

	@Nullable
	private static String[] tokens(String line) {
		if (line.isEmpty() || line.length() > MAX_LINE_LENGTH) return null;
		String[] t = line.split(" ", -1);
		for (String token : t) {
			if (!TOKEN.matcher(token).matches()) return null;
			if (token.indexOf('"') >= 0 || token.indexOf('\\') >= 0) {
				return null;
			}
		}
		return t;
	}
}

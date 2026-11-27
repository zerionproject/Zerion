package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;
import java.util.regex.Pattern;

@NotNullByDefault
public final class XmrNode {

	public enum Source { USER_OWNED, VETTED, CUSTOM, DIRECT }

	public enum HostType { ONION, IP, HOSTNAME }

	private static final Pattern V3_ONION =
			Pattern.compile("^[a-z2-7]{56}\\.onion$");
	private static final Pattern HOSTNAME = Pattern.compile(
			"^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)"
					+ "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");
	private static final Pattern NUMERIC_LAST_LABEL =
			Pattern.compile("(^|\\.)[0-9]+$");

	public final String host;
	public final int port;
	public final Source source;
	public final HostType hostType;
	public final boolean trusted;
	@Nullable
	private final byte[] ip;

	private XmrNode(String host, int port, Source source, HostType hostType,
			boolean trusted, @Nullable byte[] ip) {
		this.host = host;
		this.port = port;
		this.source = source;
		this.hostType = hostType;
		this.trusted = trusted;
		this.ip = ip;
	}

	public static XmrNode parse(String spec, Source source, boolean trusted) {
		if (spec == null) throw new IllegalArgumentException("null node");
		String s = spec.trim();
		int colon = s.lastIndexOf(':');
		if (colon <= 0 || colon == s.length() - 1) {
			throw new IllegalArgumentException("expected host:port");
		}
		String host = s.substring(0, colon).trim();
		if (host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length() - 1);
		}
		int port;
		try {
			port = Integer.parseInt(s.substring(colon + 1).trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("bad port");
		}
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("port out of range");
		}
		HostType type;
		byte[] ip = null;
		if (V3_ONION.matcher(host).matches()) {
			type = HostType.ONION;
		} else if (host.indexOf(':') >= 0) {
			ip = parseIpv6(host);
			if (ip == null) throw new IllegalArgumentException("invalid host");
			type = HostType.IP;
		} else if (NUMERIC_LAST_LABEL.matcher(host).find()) {
			ip = parseIpv4(host);
			if (ip == null) throw new IllegalArgumentException("invalid host");
			type = HostType.IP;
		} else if (HOSTNAME.matcher(host).matches()) {
			type = HostType.HOSTNAME;
		} else {
			throw new IllegalArgumentException("invalid host");
		}
		if (type == HostType.ONION && source == Source.DIRECT) {
			throw new IllegalArgumentException("an onion is never a Direct node");
		}
		if (trusted && source != Source.USER_OWNED) {
			throw new IllegalArgumentException(
					"only a user-owned node may be trusted");
		}
		XmrNode node = new XmrNode(host, port, source, type, trusted, ip);
		if (trusted && node.isUnverifiedOverTor()) {
			node = new XmrNode(host, port, source, type, false, ip);
		}
		return node;
	}

	@Nullable
	static byte[] parseIpv4(String s) {
		String[] parts = s.split("\\.", -1);
		if (parts.length != 4) return null;
		byte[] out = new byte[4];
		for (int i = 0; i < 4; i++) {
			String p = parts[i];
			if (p.isEmpty() || p.length() > 3) return null;
			if (p.length() > 1 && p.charAt(0) == '0') return null;
			int v = 0;
			for (int j = 0; j < p.length(); j++) {
				char c = p.charAt(j);
				if (c < '0' || c > '9') return null;
				v = v * 10 + (c - '0');
			}
			if (v > 255) return null;
			out[i] = (byte) v;
		}
		return out;
	}

	@Nullable
	static byte[] parseIpv6(String s) {
		if (s.isEmpty() || s.length() > 39) return null;
		int gap = s.indexOf("::");
		if (gap >= 0 && s.indexOf("::", gap + 1) >= 0) return null;
		int[] head = groups(gap >= 0 ? s.substring(0, gap) : s);
		int[] tail = groups(gap >= 0 ? s.substring(gap + 2) : "");
		if (head == null || tail == null) return null;
		int n = head.length + tail.length;
		if (gap >= 0 ? n > 7 : n != 8) return null;
		byte[] out = new byte[16];
		for (int i = 0; i < head.length; i++) {
			out[2 * i] = (byte) (head[i] >> 8);
			out[2 * i + 1] = (byte) head[i];
		}
		for (int i = 0; i < tail.length; i++) {
			int g = 8 - tail.length + i;
			out[2 * g] = (byte) (tail[i] >> 8);
			out[2 * g + 1] = (byte) tail[i];
		}
		return out;
	}

	@Nullable
	private static int[] groups(String s) {
		if (s.isEmpty()) return new int[0];
		String[] parts = s.split(":", -1);
		int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			String p = parts[i];
			if (p.isEmpty() || p.length() > 4) return null;
			int v = 0;
			for (int j = 0; j < p.length(); j++) {
				int d = Character.digit(p.charAt(j), 16);
				if (d < 0 || p.charAt(j) > 0x7f) return null;
				v = (v << 4) | d;
			}
			out[i] = v;
		}
		return out;
	}

	public boolean isOnion() {
		return hostType == HostType.ONION;
	}

	public boolean usesTor() {
		return source != Source.DIRECT;
	}

	public boolean isPrivateAddress() {
		if (hostType == HostType.HOSTNAME) {
			return host.equalsIgnoreCase("localhost");
		}
		byte[] b = ip;
		if (b == null) return false;
		int b0 = b[0] & 0xff;
		int b1 = b[1] & 0xff;
		if (b.length == 4) {
			return b0 == 10 || b0 == 127 || (b0 == 172 && b1 >= 16 && b1 <= 31)
					|| (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254);
		}
		if ((b0 & 0xfe) == 0xfc) return true;
		if (b0 == 0xfe && (b1 & 0xc0) == 0x80) return true;
		if (b0 == 0xfe && (b1 & 0xc0) == 0xc0) return true;
		for (int i = 0; i < 15; i++) {
			if (b[i] != 0) return false;
		}
		return b[15] == 1;
	}

	public boolean isUnverifiedOverTor() {
		return usesTor() && !isOnion() && !isPrivateAddress();
	}

	public boolean requiresLocalDns() {
		return !usesTor() && hostType == HostType.HOSTNAME;
	}

	public String address() {
		return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
	}

	public String endpointId() {
		return (usesTor() ? "tor" : "direct") + ":" + canonicalEndpoint();
	}

	private String canonicalEndpoint() {
		String h = host;
		if (hostType == HostType.HOSTNAME) {
			h = host.toLowerCase(Locale.ROOT);
		} else if (ip != null && ip.length == 16) {
			h = canonicalIpv6(ip);
		}
		return (h.indexOf(':') >= 0 ? "[" + h + "]" : h) + ":" + port;
	}

	private static String canonicalIpv6(byte[] b) {
		StringBuilder sb = new StringBuilder(39);
		for (int i = 0; i < 16; i += 2) {
			if (i > 0) sb.append(':');
			int g = ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
			sb.append(Integer.toHexString(g));
		}
		return sb.toString();
	}

	public String shortLabel() {
		String h = isOnion() ? host.substring(0, Math.min(10, host.length())) + "…"
				: host;
		return h + ":" + port;
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) return true;
		if (!(o instanceof XmrNode)) return false;
		XmrNode n = (XmrNode) o;
		return port == n.port && host.equals(n.host) && source == n.source
				&& trusted == n.trusted;
	}

	@Override
	public int hashCode() {
		return (host.hashCode() * 31 + port) * 31 + source.hashCode();
	}
}

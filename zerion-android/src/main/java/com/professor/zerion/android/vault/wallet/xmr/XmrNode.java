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
	private static final Pattern IPV4 = Pattern.compile(
			"^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
	private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]+$");
	private static final Pattern HOSTNAME = Pattern.compile(
			"^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)"
					+ "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");

	public final String host;
	public final int port;
	public final Source source;
	public final HostType hostType;
	public final boolean trusted;

	private XmrNode(String host, int port, Source source, HostType hostType,
			boolean trusted) {
		this.host = host;
		this.port = port;
		this.source = source;
		this.hostType = hostType;
		this.trusted = trusted;
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
		if (V3_ONION.matcher(host).matches()) {
			type = HostType.ONION;
		} else if (IPV4.matcher(host).matches()
				|| (host.contains(":") && IPV6.matcher(host).matches())) {
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
		return new XmrNode(host, port, source, type, trusted);
	}

	public boolean isOnion() {
		return hostType == HostType.ONION;
	}

	public boolean usesTor() {
		return source != Source.DIRECT;
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
		} else if (hostType == HostType.IP && host.indexOf(':') >= 0) {
			h = canonicalIpv6(host);
		}
		return (h.indexOf(':') >= 0 ? "[" + h + "]" : h) + ":" + port;
	}

	private static String canonicalIpv6(String literal) {
		try {
			java.net.InetAddress a = java.net.InetAddress.getByName(literal);
			if (a instanceof java.net.Inet6Address) {
				byte[] b = a.getAddress();
				StringBuilder sb = new StringBuilder(39);
				for (int i = 0; i < 16; i += 2) {
					if (i > 0) sb.append(':');
					int g = ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
					sb.append(Integer.toHexString(g));
				}
				return sb.toString();
			}
		} catch (Exception ignored) {
		}
		return literal.toLowerCase(Locale.ROOT);
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

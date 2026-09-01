package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class ElectrumEndpoint {

	public enum Mode {
		ONION,
		TLS,
		PLAINTEXT
	}

	public final String host;
	public final int port;
	public final Mode mode;
	public final boolean local;
	public final boolean direct;
	@Nullable
	public final String pinSha256;

	public ElectrumEndpoint(String host, int port, Mode mode, boolean local,
			@Nullable String pinSha256) {
		this(host, port, mode, local, false, pinSha256);
	}

	public ElectrumEndpoint(String host, int port, Mode mode, boolean local,
			boolean direct, @Nullable String pinSha256) {
		if (host.trim().isEmpty()) {
			throw new IllegalArgumentException("host is empty");
		}
		if (port <= 0 || port > 65535) {
			throw new IllegalArgumentException("bad port " + port);
		}
		if (mode == Mode.ONION && !host.toLowerCase().endsWith(".onion")) {
			throw new IllegalArgumentException("onion mode needs a .onion host");
		}
		if (mode == Mode.ONION && local) {
			throw new IllegalArgumentException("onion cannot be local");
		}
		if (mode != Mode.TLS && pinSha256 != null) {
			throw new IllegalArgumentException("pin only applies to TLS");
		}
		if (direct && mode != Mode.TLS) {
			throw new IllegalArgumentException(
					"direct routing requires verified TLS");
		}
		if (direct && local) {
			throw new IllegalArgumentException(
					"direct and local are mutually exclusive");
		}
		this.host = host.trim();
		this.port = port;
		this.mode = mode;
		this.local = local;
		this.direct = direct;
		this.pinSha256 = pinSha256 == null ? null
				: pinSha256.trim().toLowerCase();
	}

	public ElectrumEndpoint asDirect() {
		if (mode != Mode.TLS) {
			throw new IllegalArgumentException(
					"direct routing requires a verified TLS endpoint");
		}
		return new ElectrumEndpoint(host, port, mode, false, true, pinSha256);
	}

	public boolean isOnion() {
		return mode == Mode.ONION;
	}

	public boolean tls() {
		return mode == Mode.TLS;
	}

	public boolean viaTor() {
		return !local && !direct;
	}

	public boolean pinned() {
		return pinSha256 != null && !pinSha256.isEmpty();
	}

	public static Mode inferMode(String host, int port) {
		if (host.toLowerCase().endsWith(".onion")) {
			return Mode.ONION;
		}
		return port == 50002 ? Mode.TLS : Mode.PLAINTEXT;
	}

	public static boolean isLanHost(String host) {
		String h = host.toLowerCase().trim();
		if (h.equals("localhost") || h.equals("127.0.0.1")
				|| h.endsWith(".local")) {
			return true;
		}
		if (h.startsWith("10.") || h.startsWith("192.168.")) {
			return true;
		}
		if (h.startsWith("172.")) {
			int dot = h.indexOf('.', 4);
			if (dot > 4) {
				try {
					int second = Integer.parseInt(h.substring(4, dot));
					return second >= 16 && second <= 31;
				} catch (NumberFormatException ignored) {
					return false;
				}
			}
		}
		return false;
	}

	public static String preferredDefaultSpec(@Nullable String onionSpec,
			String tlsSpec) {
		return onionSpec != null && !onionSpec.trim().isEmpty()
				? onionSpec.trim() : tlsSpec;
	}

	public static ElectrumEndpoint fromUserInput(String host, int port,
			@Nullable String pinSha256) {
		String h = host.trim();
		if (h.toLowerCase().endsWith(".onion")) {
			return new ElectrumEndpoint(h, port, Mode.ONION, false, null);
		}
		boolean local = isLanHost(h);
		Mode mode = port == 50002 ? Mode.TLS : Mode.PLAINTEXT;
		return new ElectrumEndpoint(h, port, mode, local,
				mode == Mode.TLS ? pinSha256 : null);
	}

	public String encode() {
		return mode.name().toLowerCase() + "|" + (local ? "1" : "0") + "|"
				+ host + ":" + port + "|" + (pinSha256 == null ? "" : pinSha256);
	}

	public static ElectrumEndpoint parse(String spec) {
		String s = spec.trim();
		String[] parts = s.split("\\|", -1);
		if (parts.length >= 3) {
			Mode mode = Mode.valueOf(parts[0].trim().toUpperCase());
			boolean local = "1".equals(parts[1].trim());
			int colon = parts[2].lastIndexOf(':');
			if (colon < 0) {
				throw new IllegalArgumentException("bad host:port");
			}
			String host = parts[2].substring(0, colon).trim();
			int port = Integer.parseInt(parts[2].substring(colon + 1).trim());
			String pin = parts.length >= 4 && !parts[3].trim().isEmpty()
					? parts[3].trim() : null;
			return new ElectrumEndpoint(host, port, mode, local, pin);
		}
		int colon = s.lastIndexOf(':');
		if (colon < 0) {
			throw new IllegalArgumentException("bad host:port");
		}
		String host = s.substring(0, colon).trim();
		int port = Integer.parseInt(s.substring(colon + 1).trim());
		return new ElectrumEndpoint(host, port, inferMode(host, port), false,
				null);
	}
}

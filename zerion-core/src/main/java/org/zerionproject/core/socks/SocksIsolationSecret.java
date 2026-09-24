package org.zerionproject.core.socks;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.util.StringUtils;

import java.security.SecureRandom;

/**
 * The per-process half of the SOCKS isolation credentials. It is drawn once
 * when the process starts, shared by every Tor socket factory of the process
 * so that one destination maps to one circuit across them, never written
 * anywhere, and replaced by a fresh value on the next start so that circuits
 * are not shared across process lifetimes.
 */
@NotNullByDefault
public final class SocksIsolationSecret {

	private static final int BYTES = 16;

	private final String value;

	public SocksIsolationSecret(SecureRandom random) {
		byte[] b = new byte[BYTES];
		random.nextBytes(b);
		value = StringUtils.toHexString(b);
	}

	String value() {
		return value;
	}

	@Override
	public String toString() {
		return "SocksIsolationSecret";
	}
}

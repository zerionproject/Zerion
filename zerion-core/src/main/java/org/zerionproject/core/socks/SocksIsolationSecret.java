package org.zerionproject.core.socks;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.util.StringUtils;

import java.security.SecureRandom;

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

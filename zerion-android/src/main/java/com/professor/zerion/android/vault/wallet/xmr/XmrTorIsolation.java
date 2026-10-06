package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

@NotNullByDefault
public final class XmrTorIsolation {

	static final String PURPOSE_SYNC = "sync";
	static final String PURPOSE_RELAY = "relay";
	private static final String USER_PREFIX = "zx-";
	private static final int USER_HEX_CHARS = 32;
	private static final int SECRET_BYTES = 16;

	private static final String PROCESS_SECRET = randomHex();

	private XmrTorIsolation() {
	}

	public static boolean isProcessSecret(String candidate) {
		return com.professor.zerion.android.vault.net.TorSocksGate
				.constantTimeEquals(PROCESS_SECRET, candidate);
	}

	public static String syncProxy(int socksPort, String walletId) {
		return proxy(socksPort, walletId, PURPOSE_SYNC);
	}

	public static String relayProxy(int socksPort, String walletId) {
		return proxy(socksPort, walletId, PURPOSE_RELAY);
	}

	static String proxy(int socksPort, String walletId, String purpose) {
		return proxyWithSecret(socksPort, walletId, purpose, PROCESS_SECRET);
	}

	static String proxyWithSecret(int socksPort, String walletId,
			String purpose, String secret) {
		return "socks5://" + username(walletId, purpose) + ":" + secret
				+ "@127.0.0.1:" + socksPort;
	}

	static String username(String walletId, String purpose) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(walletId.getBytes(StandardCharsets.UTF_8));
			md.update((byte) 0);
			md.update(purpose.getBytes(StandardCharsets.UTF_8));
			return USER_PREFIX + hex(md.digest()).substring(0, USER_HEX_CHARS);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String randomHex() {
		byte[] b = new byte[SECRET_BYTES];
		new SecureRandom().nextBytes(b);
		return hex(b);
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) {
			sb.append(Character.forDigit((x >> 4) & 0xF, 16));
			sb.append(Character.forDigit(x & 0xF, 16));
		}
		return sb.toString();
	}
}

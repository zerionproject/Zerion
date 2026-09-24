package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Tor stream isolation for Monero daemon traffic. wallet2 reaches the daemon
 * through the SOCKS proxy string handed to its init; a bare host:port selects
 * SOCKS4a with no authentication, so every Monero stream of the process was
 * eligible to share one circuit with every other, letting a node link the
 * user's wallets to each other and a wallet's syncing to its relay. The
 * vendored wallet2 parses {@code socks5://user:pass@host:port} and performs
 * SOCKS5 username/password authentication, and Tor isolates streams by those
 * credentials (IsolateSOCKSAuth), so distinct credentials yield distinct
 * circuits. The username is a digest of the wallet identity and the purpose
 * (never the raw wallet id, and not secret: it is stable across processes and
 * devices, and only the loopback gate and the local Tor ever see it); the
 * password is drawn once per process so
 * circuits are not shared across process lifetimes. Neither is persisted:
 * wallet2 keeps the proxy string in memory only and nothing here writes it.
 */
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

	/**
	 * Whether {@code candidate} is this process's SOCKS password, compared
	 * in constant time. The loopback relay the native wallet reaches Tor
	 * through accepts only this password.
	 */
	public static boolean isProcessSecret(String candidate) {
		return com.professor.zerion.android.vault.net.TorSocksGate
				.constantTimeEquals(PROCESS_SECRET, candidate);
	}

	/** Proxy string for the view-only sync session of a wallet. */
	public static String syncProxy(int socksPort, String walletId) {
		return proxy(socksPort, walletId, PURPOSE_SYNC);
	}

	/** Proxy string for the spend-capable relay session of a wallet. */
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

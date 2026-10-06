package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class TorIsolation {

	private TorIsolation() {
	}

	public static String scan(String walletId) {
		return walletId;
	}

	public static String broadcast(String walletId) {
		return walletId + "-b";
	}

	public static String silentPayment(String walletId) {
		return walletId + "-sp";
	}

	public static String price(String walletId) {
		return walletId + "-price";
	}

	public static String payjoin(String walletId) {
		return walletId + "-pj";
	}

	public static String ephemeral(String purpose) {
		byte[] nonce = new byte[4];
		new java.security.SecureRandom().nextBytes(nonce);
		StringBuilder sb = new StringBuilder(purpose).append('-');
		for (byte b : nonce) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

	public static String socksUser(String tag) {
		return "zw-" + tag;
	}

	public static String socksPassword(String tag) {
		return tag;
	}
}

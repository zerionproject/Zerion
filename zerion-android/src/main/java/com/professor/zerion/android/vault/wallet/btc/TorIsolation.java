package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Central definition of the Tor stream-isolation context for each wallet
 * activity. Every context is derived from the wallet identifier so activities of
 * different wallets never share a circuit, and each activity of one wallet
 * (scan, broadcast, Silent Payment scan, price, Payjoin) gets a distinct,
 * stable context so they are not linkable to each other over one circuit. The
 * contexts are stable per wallet and per purpose, not per request, so a fresh
 * Tor identity is not created for every call. Stream isolation is keyed on the
 * SOCKS username and password derived here.
 */
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

	public static String socksUser(String tag) {
		return "zw-" + tag;
	}

	public static String socksPassword(String tag) {
		return tag;
	}
}

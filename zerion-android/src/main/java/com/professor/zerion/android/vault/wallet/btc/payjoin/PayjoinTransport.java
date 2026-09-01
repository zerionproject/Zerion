package com.professor.zerion.android.vault.wallet.btc.payjoin;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Sends the original proposal to the Payjoin receiver through the untrusted
 * directory/relay infrastructure over Tor and returns the receiver's response,
 * or null on any failure (relay unavailable, Tor down, timeout). There is no
 * clearnet fallback. The relay is external and untrusted; it can observe only
 * the encrypted request timing and size, never wallet keys, the wallet graph,
 * the full balance, unrelated UTXOs, or labels. Implementations must use a
 * dedicated Payjoin Tor isolation context, distinct from Electrum and
 * messaging traffic.
 */
@NotNullByDefault
public interface PayjoinTransport {

	@Nullable
	byte[] exchange(String pjUri, byte[] originalProposal, int socksPort,
			String isolationTag);
}

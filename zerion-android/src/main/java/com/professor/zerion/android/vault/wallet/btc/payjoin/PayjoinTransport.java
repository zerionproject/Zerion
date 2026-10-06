package com.professor.zerion.android.vault.wallet.btc.payjoin;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface PayjoinTransport {

	@Nullable
	byte[] exchange(String pjUri, byte[] originalProposal, int socksPort,
			String isolationTag);
}

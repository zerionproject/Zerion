package com.professor.zerion.android.vault.wallet.btc;

import java.io.IOException;

public final class BroadcastUncertainException extends IOException {

	public final String txid;

	public BroadcastUncertainException(String txid, Throwable cause) {
		super("Broadcast outcome uncertain", cause);
		this.txid = txid;
	}
}

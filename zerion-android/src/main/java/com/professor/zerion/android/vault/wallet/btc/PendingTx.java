package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

@NotNullByDefault
public final class PendingTx {

	public static final String BROADCASTING = "broadcasting";
	public static final String POSSIBLY_SENT = "possibly_sent";
	public static final String SENT = "sent";
	public static final String FAILED = "failed";

	public final String id;
	public final String txid;
	public final String rawHex;
	public final List<String> outpoints;
	public final String state;
	public final long createdAt;
	public final long netSat;

	public PendingTx(String id, String txid, String rawHex,
			List<String> outpoints, String state, long createdAt, long netSat) {
		this.id = id;
		this.txid = txid;
		this.rawHex = rawHex;
		this.outpoints = outpoints;
		this.state = state;
		this.createdAt = createdAt;
		this.netSat = netSat;
	}

	public PendingTx withState(String newState) {
		return new PendingTx(id, txid, rawHex, new ArrayList<>(outpoints),
				newState, createdAt, netSat);
	}

	public boolean isUnresolved() {
		return BROADCASTING.equals(state) || POSSIBLY_SENT.equals(state);
	}
}

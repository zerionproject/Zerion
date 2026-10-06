package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public enum XmrSyncState {
	LOCKED,
	STARTING_TOR,
	CONNECTING,
	CONNECTED,
	SYNCHRONIZING,
	SYNCED,
	OFFLINE,
	ERROR
}

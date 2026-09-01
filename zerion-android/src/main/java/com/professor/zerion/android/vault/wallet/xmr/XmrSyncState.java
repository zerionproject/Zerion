package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Explicit sync lifecycle. There is no state that shows "Synced" while a scan
 * is still in progress; SYNCED is reached only when the wallet height has caught
 * up to the daemon height. OFFLINE means fail-closed (no Tor / no reachable
 * daemon) with the local wallet still usable; ERROR is a recoverable fault.
 */
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

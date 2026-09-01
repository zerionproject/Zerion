package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Immutable snapshot of sync progress. Heights distinguish NOT-YET-SCANNED from
 * CONFIRMED-ZERO: while {@code walletHeight < daemonHeight} the balance is
 * provisional and {@link #scanComplete()} is false, so the UI must not present a
 * zero balance as final. Balances are atomic (piconero). {@code checking} is
 * true while a user-requested refresh is being serviced.
 */
@NotNullByDefault
public final class XmrSyncStatus {

	public final XmrSyncState state;
	public final long walletHeight;
	public final long daemonHeight;
	public final long balanceAtomic;
	public final long unlockedAtomic;
	@Nullable
	public final String nodeLabel;
	@Nullable
	public final XmrError error;
	public final boolean checking;

	public XmrSyncStatus(XmrSyncState state, long walletHeight, long daemonHeight,
			long balanceAtomic, long unlockedAtomic, @Nullable String nodeLabel,
			@Nullable XmrError error) {
		this(state, walletHeight, daemonHeight, balanceAtomic, unlockedAtomic,
				nodeLabel, error, false);
	}

	public XmrSyncStatus(XmrSyncState state, long walletHeight, long daemonHeight,
			long balanceAtomic, long unlockedAtomic, @Nullable String nodeLabel,
			@Nullable XmrError error, boolean checking) {
		this.state = state;
		this.walletHeight = walletHeight;
		this.daemonHeight = daemonHeight;
		this.balanceAtomic = balanceAtomic;
		this.unlockedAtomic = unlockedAtomic;
		this.nodeLabel = nodeLabel;
		this.error = error;
		this.checking = checking;
	}

	public static XmrSyncStatus of(XmrSyncState state) {
		return new XmrSyncStatus(state, 0, 0, 0, 0, null, null);
	}

	public long remainingBlocks() {
		long r = daemonHeight - walletHeight;
		return r > 0 ? r : 0;
	}

	public boolean scanComplete() {
		return daemonHeight > 0 && walletHeight >= daemonHeight;
	}

	public XmrSyncStatus withState(XmrSyncState s) {
		return new XmrSyncStatus(s, walletHeight, daemonHeight, balanceAtomic,
				unlockedAtomic, nodeLabel, error, checking);
	}
}

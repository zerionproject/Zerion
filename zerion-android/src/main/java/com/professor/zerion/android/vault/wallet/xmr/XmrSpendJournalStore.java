package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Durable, fail-closed access to the spend journal. It reads and writes the
 * journal through {@link XmrStore}, whose journal writes return only after the
 * record is committed to disk, so a relay can be gated on a successful write.
 * Reading is strict: a present journal that will not parse, or a storage read
 * that fails, is reported as {@link Kind#CORRUPTED}, never as absent, so an
 * unreadable safety record can never let a new spend through. Clearing is
 * package-private and belongs to the reconciliation authority.
 */
@NotNullByDefault
public final class XmrSpendJournalStore {

	public enum Kind { ABSENT, PRESENT, CORRUPTED }

	public static final class Status {
		public final Kind kind;
		@Nullable
		public final XmrSpendJournal journal;

		private Status(Kind kind, @Nullable XmrSpendJournal journal) {
			this.kind = kind;
			this.journal = journal;
		}

		/** True unless the wallet is provably free of any journal. */
		public boolean quarantined() {
			return kind != Kind.ABSENT;
		}
	}

	private static final Status ABSENT = new Status(Kind.ABSENT, null);
	private static final Status CORRUPTED = new Status(Kind.CORRUPTED, null);

	private final XmrStore store;

	public XmrSpendJournalStore(XmrStore store) {
		this.store = store;
	}

	public Status read(String walletId) {
		String raw;
		try {
			raw = store.readSpendJournal(walletId);
		} catch (Exception unreadable) {
			return CORRUPTED;
		}
		if (raw == null) return ABSENT;
		try {
			return new Status(Kind.PRESENT,
					XmrSpendJournal.parse(walletId, raw));
		} catch (XmrError.XmrException corrupt) {
			return CORRUPTED;
		}
	}

	public boolean isQuarantined(String walletId) {
		return read(walletId).quarantined();
	}

	/**
	 * Persist the journal durably. Returns only after the record is committed;
	 * on any storage failure it throws and the caller must not relay.
	 */
	public void writeDurably(XmrSpendJournal journal)
			throws XmrError.XmrException {
		try {
			store.writeSpendJournal(journal.walletId(), journal.serialize());
		} catch (Exception e) {
			throw new XmrError.XmrException(XmrError.STORAGE_COMMIT_FAILED, e);
		}
	}

	/**
	 * Remove the journal. Owned by the reconciliation authority and the manager's
	 * quarantine-clearing path only; it is deliberately not a public API, so no
	 * UI action can dismiss a quarantine.
	 */
	void clear(String walletId) throws Exception {
		store.removeSpendJournal(walletId);
	}
}

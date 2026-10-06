package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

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

	public void writeDurably(XmrSpendJournal journal)
			throws XmrError.XmrException {
		try {
			store.writeSpendJournal(journal.walletId(), journal.serialize());
		} catch (Exception e) {
			throw new XmrError.XmrException(XmrError.STORAGE_COMMIT_FAILED, e);
		}
	}

	void clear(String walletId) throws Exception {
		store.removeSpendJournal(walletId);
	}
}

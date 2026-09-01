package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.Nullable;

import com.professor.zerion.android.vault.wallet.WalletCoin;
import com.professor.zerion.android.vault.wallet.WalletRecord;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The store is durable-by-contract and strictly fail-closed: a present journal
 * reads back, an unparseable or unreadable one is CORRUPTED (still quarantined),
 * a missing one is ABSENT, a write failure surfaces so a relay cannot proceed,
 * and wallets are isolated from each other.
 */
public class XmrSpendJournalStoreTest {

	private static final String A = "wallet-A";
	private static final String B = "wallet-B";
	private static final String AF =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String T1 =
			"1111111111111111111111111111111111111111111111111111111111111111";
	private static final String EP = "direct:203.0.113.5:18081";

	private static XmrSpendJournal journal(String wid)
			throws XmrError.XmrException {
		return XmrSpendJournal.create(XmrSpendJournal.State.UNCERTAIN, wid, AF,
				Arrays.asList(T1), EP, 1724900000000L, Collections.emptyList());
	}

	@Test
	public void absentWhenNothingWritten() {
		XmrSpendJournalStore store = new XmrSpendJournalStore(new FakeStore());
		XmrSpendJournalStore.Status s = store.read(A);
		assertEquals(XmrSpendJournalStore.Kind.ABSENT, s.kind);
		assertFalse(s.quarantined());
	}

	@Test
	public void writtenJournalReadsBackAndQuarantines() throws Exception {
		XmrSpendJournalStore store = new XmrSpendJournalStore(new FakeStore());
		store.writeDurably(journal(A));
		XmrSpendJournalStore.Status s = store.read(A);
		assertEquals(XmrSpendJournalStore.Kind.PRESENT, s.kind);
		assertTrue(s.quarantined());
		assertNotNull(s.journal);
		assertEquals(Arrays.asList(T1), s.journal.txids());
		assertTrue(store.isQuarantined(A));
	}

	@Test
	public void unparseableJournalIsCorruptNotAbsent() {
		FakeStore fake = new FakeStore();
		fake.journals.put(A, "totally not a journal");
		XmrSpendJournalStore store = new XmrSpendJournalStore(fake);
		XmrSpendJournalStore.Status s = store.read(A);
		assertEquals(XmrSpendJournalStore.Kind.CORRUPTED, s.kind);
		assertTrue("a corrupt journal still quarantines", s.quarantined());
	}

	@Test
	public void unreadableStorageIsCorruptNotAbsent() {
		FakeStore fake = new FakeStore();
		fake.failRead = true;
		XmrSpendJournalStore store = new XmrSpendJournalStore(fake);
		XmrSpendJournalStore.Status s = store.read(A);
		assertEquals(XmrSpendJournalStore.Kind.CORRUPTED, s.kind);
		assertTrue(s.quarantined());
	}

	@Test
	public void writeFailurePropagatesSoRelayCannotProceed() {
		FakeStore fake = new FakeStore();
		fake.failWrite = true;
		XmrSpendJournalStore store = new XmrSpendJournalStore(fake);
		try {
			store.writeDurably(journal(A));
			fail("a journal write failure must surface");
		} catch (XmrError.XmrException e) {
			assertEquals(XmrError.STORAGE_COMMIT_FAILED, e.error);
		}
	}

	@Test
	public void clearRemovesTheJournal() throws Exception {
		XmrSpendJournalStore store = new XmrSpendJournalStore(new FakeStore());
		store.writeDurably(journal(A));
		assertTrue(store.isQuarantined(A));
		store.clear(A);
		assertFalse(store.isQuarantined(A));
		assertEquals(XmrSpendJournalStore.Kind.ABSENT, store.read(A).kind);
	}

	@Test
	public void walletsAreIsolated() throws Exception {
		XmrSpendJournalStore store = new XmrSpendJournalStore(new FakeStore());
		store.writeDurably(journal(A));
		assertTrue(store.isQuarantined(A));
		assertFalse("B is unaffected by A's journal", store.isQuarantined(B));
	}

	private static final class FakeStore implements XmrStore {
		final Map<String, String> journals = new HashMap<>();
		boolean failRead = false;
		boolean failWrite = false;

		@Nullable
		@Override
		public String readSpendJournal(String walletId) throws Exception {
			if (failRead) throw new java.io.IOException("read");
			return journals.get(walletId);
		}

		@Override
		public void writeSpendJournal(String walletId, String journal)
				throws Exception {
			if (failWrite) throw new java.io.IOException("write");
			journals.put(walletId, journal);
		}

		@Override
		public void removeSpendJournal(String walletId) {
			journals.remove(walletId);
		}

		@Override
		public String createWallet(WalletCoin coin, String name,
				char[] mnemonic, @Nullable char[] password) {
			return "id";
		}

		@Override
		public char[] loadMnemonicChars(String walletId,
				@Nullable char[] password) {
			return new char[0];
		}

		@Override
		public List<WalletRecord> listWallets() {
			return new ArrayList<>();
		}

		@Override
		public void deleteWallet(String walletId) {
		}

		@Nullable
		@Override
		public String readSettings() {
			return null;
		}

		@Override
		public void writeSettings(String json) {
		}

		@Override
		public Object settingsMonitor() {
			return this;
		}
	}
}

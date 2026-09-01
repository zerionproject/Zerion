package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.Nullable;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.professor.zerion.android.vault.ui.Event;
import com.professor.zerion.android.vault.wallet.WalletCoin;
import com.professor.zerion.android.vault.wallet.WalletRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic XMR-P1 tests: fail-closed authentication, atomic create,
 * malformed-seed rejection, session invalidation, and secret-buffer wiping.
 * Plain JUnit with a fake native engine and hand-written fakes for the vault
 * gate and store, so no native code and no real vault storage are touched.
 */
public class XmrWalletManagerTest {

	@Rule
	public final InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

	private FakeVaultGate vault;
	private FakeStore store;
	private FakeMoneroEngine engine;
	private XmrWalletManager mgr;
	private File tmpBase;

	@Before
	public void setUp() throws Exception {
		vault = new FakeVaultGate();
		store = new FakeStore();
		engine = new FakeMoneroEngine();
		for (String id : new String[]{"id", "A", "B"}) {
			store.records.add(new WalletRecord(id, WalletCoin.XMR, id, 0, true));
		}
		tmpBase = Files.createTempDirectory("xmrtest").toFile();
		mgr = new XmrWalletManager(tmpBase, vault, store, engine, Runnable::run);
	}

	@After
	public void tearDown() {
		deleteTree(tmpBase);
	}

	private static void deleteTree(File f) {
		File[] kids = f.listFiles();
		if (kids != null) for (File k : kids) deleteTree(k);
		f.delete();
	}

	private XmrError lastError() {
		Event<XmrError> e = mgr.getError().getValue();
		return e == null ? null : e.getIfNotHandled();
	}

	@Test
	public void createStoresSeedEncryptedAndRevealsIt() {
		mgr.createWallet("w", "pass".toCharArray());
		assertEquals("seed stored under XMR coin", 1, store.created.size());
		assertEquals(WalletCoin.XMR, store.created.get(0).coin);
		assertTrue("stored with a wallet password",
				store.created.get(0).hadPassword);
		Event<String> reveal = mgr.getSeedReveal().getValue();
		String id = reveal == null ? null : reveal.getIfNotHandled();
		assertNotNull("create leads into the backup flow", id);
		char[] seed = mgr.takePendingSeed(id);
		assertNotNull("phrase is handed over in memory", seed);
		assertEquals(25, new String(seed).trim().split("\s+").length);
		assertEquals("phrase is handed over exactly once", null,
				mgr.takePendingSeed(id));
		assertEquals("phrase is bound to its wallet id", null,
				mgr.takePendingSeed("other"));
	}

	@Test
	public void createBuildsV2SoViewOpenNeedsNoPassword() {
		mgr.createWallet("w", "pass".toCharArray());
		Event<String> reveal = mgr.getSeedReveal().getValue();
		String id = reveal == null ? null : reveal.getIfNotHandled();
		assertNotNull(id);
		assertFalse("a freshly created V2 wallet opens for view without a "
				+ "wallet password", mgr.needsPasswordToOpen(id));
	}

	@Test
	public void viewOpenOfV2WalletOpensTheBackgroundSessionWithoutPassword() {
		mgr.createWallet("w", "pass".toCharArray());
		Event<String> reveal = mgr.getSeedReveal().getValue();
		String id = reveal == null ? null : reveal.getIfNotHandled();
		assertNotNull(id);
		mgr.openWalletForView(id);
		assertTrue(mgr.isSessionValid());
		assertEquals(id, mgr.openWalletId());
	}

	@Test
	public void viewOpenOfAnUnbuiltWalletRequiresThePassword() {
		mgr.openWalletForView("id");
		assertEquals(XmrError.WALLET_NEEDS_PASSWORD, lastError());
		assertFalse(mgr.isSessionValid());
	}

	@Test
	public void openWithPasswordBuildsV2ThenViewOpensWithoutPassword() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		mgr.openWallet("id", "correct".toCharArray());
		assertTrue(mgr.isSessionValid());
		assertFalse("after building V2, view open needs no password",
				mgr.needsPasswordToOpen("id"));
	}

	@Test
	public void createRollsBackOnStorageFailure() {
		store.failCreate = true;
		mgr.createWallet("w", "pass".toCharArray());
		assertEquals(XmrError.STORAGE_COMMIT_FAILED, lastError());
		assertFalse(mgr.isSessionValid());
		assertEquals("no wallet committed", 0, store.created.size());
	}

	@Test
	public void createRejectsEmptyPassword() {
		mgr.createWallet("w", new char[0]);
		assertEquals(XmrError.EMPTY_PASSWORD, lastError());
		assertEquals(0, store.created.size());
	}

	@Test
	public void createWipesPasswordBuffer() {
		char[] pw = "secretpw".toCharArray();
		mgr.createWallet("w", pw);
		assertArrayEquals(new char[pw.length], pw);
	}

	@Test
	public void importAcceptsValidSeed() {
		mgr.importWallet("w", FakeMoneroEngine.FAKE_SEED.toCharArray(), 100,
				"pass".toCharArray());
		assertEquals(1, store.created.size());
	}

	@Test
	public void importRejectsMalformedSeedBeforeCommit() {
		mgr.importWallet("w", "too few words".toCharArray(), 0,
				"pass".toCharArray());
		assertEquals(XmrError.MALFORMED_SEED, lastError());
		assertEquals("no wallet committed for bad seed", 0, store.created.size());
	}

	@Test
	public void openWithCorrectPasswordOpensSession() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		mgr.openWallet("id", "correct".toCharArray());
		assertTrue(mgr.isSessionValid());
		assertEquals("id", mgr.openWalletId());
	}

	@Test
	public void wrongPasswordRejectedNoSession() {
		store.throwOnLoad = new SecurityException("bad");
		mgr.openWallet("id", "wrong".toCharArray());
		assertEquals(XmrError.WRONG_PASSWORD, lastError());
		assertFalse(mgr.isSessionValid());
	}

	@Test
	public void corruptedItemNotReportedAsWrongPassword() {
		store.throwOnLoad = new java.io.IOException("corrupt");
		mgr.openWallet("id", "pw".toCharArray());
		assertEquals(XmrError.CORRUPTED_ITEM, lastError());
		assertFalse(mgr.isSessionValid());
	}

	@Test
	public void emptyPasswordRejectedOnOpen() {
		mgr.openWallet("id", new char[0]);
		assertEquals(XmrError.EMPTY_PASSWORD, lastError());
		assertFalse(mgr.isSessionValid());
	}

	@Test
	public void walletACredentialCannotOpenB() {
		store.throwOnLoad = new SecurityException("wrong for B");
		mgr.openWallet("B", "A-password".toCharArray());
		assertEquals(XmrError.WRONG_PASSWORD, lastError());
		assertFalse(mgr.isSessionValid());
	}

	@Test
	public void openWipesPasswordBuffer() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		char[] pw = "opensecret".toCharArray();
		mgr.openWallet("id", pw);
		assertArrayEquals(new char[pw.length], pw);
	}

	@Test
	public void engineUnavailableFailsClosed() {
		engine.available = false;
		mgr.openWallet("id", "pw".toCharArray());
		assertEquals(XmrError.NATIVE_UNAVAILABLE, lastError());
		assertFalse(mgr.isSessionValid());
	}

	@Test
	public void openWhenVaultLockedFailsClosed() {
		vault.unlocked = false;
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		mgr.openWallet("id", "pw".toCharArray());
		assertEquals(XmrError.SESSION_INVALIDATED, lastError());
		assertFalse(mgr.isSessionValid());
	}

	@Test
	public void staleGenerationInvalidatesSession() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		mgr.openWallet("id", "pw".toCharArray());
		assertTrue(mgr.isSessionValid());
		vault.generation++;
		vault.unlocked = false;
		assertFalse(mgr.isSessionValid());
	}

	@Test
	public void vaultLockWipesPendingRecoveryPhrase() {
		mgr.createWallet("w", "pass".toCharArray());
		Event<String> reveal = mgr.getSeedReveal().getValue();
		String id = reveal == null ? null : reveal.getIfNotHandled();
		assertNotNull(id);
		vault.fireLock();
		assertEquals("a lock drops the in-memory phrase hand-off", null,
				mgr.takePendingSeed(id));
	}

	@Test
	public void vaultLockCallbackClosesNativeHandle() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		mgr.openWallet("id", "pw".toCharArray());
		int before = engine.closeCount;
		vault.fireLock();
		assertFalse(mgr.isSessionValid());
		assertTrue("native handle closed on lock", engine.closeCount > before);
	}

	@Test
	public void explicitCloseInvalidatesAndClosesNativeHandle() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		mgr.openWallet("id", "pw".toCharArray());
		int before = engine.closeCount;
		mgr.closeSession();
		assertFalse(mgr.isSessionValid());
		assertTrue(engine.closeCount > before);
	}

	@Test
	public void switchingWalletsClosesOldHandle() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		mgr.openWallet("A", "pw".toCharArray());
		int afterFirst = engine.closeCount;
		mgr.openWallet("B", "pw".toCharArray());
		assertEquals("B", mgr.openWalletId());
		assertTrue("old handle closed on switch", engine.closeCount > afterFirst);
	}

	@Test
	public void loadWalletsShowsOnlyXmrRegardlessOfName() {
		store.records.clear();
		store.records.add(new WalletRecord("btc1", WalletCoin.BTC,
				"my monero xmr wallet", 0, true));
		store.records.add(new WalletRecord("xmr1", WalletCoin.XMR,
				"totally bitcoin btc", 0, true));
		mgr.loadWallets();
		List<WalletRecord> shown = mgr.getWallets().getValue();
		assertEquals("only the XMR-coin wallet is shown", 1, shown.size());
		assertEquals(WalletCoin.XMR, shown.get(0).coin);
		assertEquals("coin comes from the record, not the name", "xmr1",
				shown.get(0).id);
	}

	@Test
	public void successfulDeleteUpdatesListFromPersistedStateBeforeEvent() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		mgr.loadWallets();
		assertEquals(3, mgr.getWallets().getValue().size());
		mgr.deleteWallet("A", "pw".toCharArray());
		List<WalletRecord> shown = mgr.getWallets().getValue();
		assertEquals("list reflects persisted state at the commit point", 2,
				shown.size());
		for (WalletRecord w : shown) {
			assertFalse("deleted wallet is gone from the list",
					w.id.equals("A"));
		}
		assertTrue("B is untouched", store.records.stream()
				.anyMatch(r -> r.id.equals("B")));
		Event<String> ev = mgr.getWalletDeleted().getValue();
		assertEquals("completion event names the deleted wallet", "A",
				ev == null ? null : ev.getIfNotHandled());
	}

	@Test
	public void failedDeleteKeepsWalletVisibleAndPostsNoSuccess() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		store.failDelete = true;
		mgr.loadWallets();
		mgr.deleteWallet("A", "pw".toCharArray());
		assertEquals("no success event on a failed delete", null,
				mgr.getWalletDeleted().getValue());
		assertEquals(XmrError.UNKNOWN, lastError());
		List<WalletRecord> shown = mgr.getWallets().getValue();
		assertTrue("wallet remains listed", shown.stream()
				.anyMatch(w -> w.id.equals("A")));
		assertTrue("nothing removed from the store", store.deleted.isEmpty());
	}

	@Test
	public void reopeningTheOpenWalletReusesTheSessionAndOpensNoSecondHandle() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		mgr.openWallet("A", "pw".toCharArray());
		assertTrue(mgr.isSessionValid());
		int opens = engine.openCount;
		int closes = engine.closeCount;
		mgr.openWallet("A", "pw".toCharArray());
		assertEquals("no second native handle for the same wallet", opens,
				engine.openCount);
		assertEquals("the existing session is not closed", closes,
				engine.closeCount);
		assertEquals("A", mgr.openWalletId());
		Event<String> ev = mgr.getSessionOpened().getValue();
		assertEquals("the second surface is told the wallet is open", "A",
				ev == null ? null : ev.getIfNotHandled());
	}

	@Test
	public void openIsRejectedWhileAnExclusiveOperationRuns() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		mgr.openWallet("A", "pw".toCharArray());
		final XmrError[] seen = new XmrError[1];
		store.onDelete = () -> {
			mgr.openWallet("B", "pw".toCharArray());
			seen[0] = lastError();
		};
		mgr.deleteWallet("A", "pw".toCharArray());
		assertEquals("open during delete is rejected as busy", XmrError.BUSY,
				seen[0]);
		assertFalse("B did not open behind the delete",
				"B".equals(mgr.openWalletId()));
		assertFalse("the guard is released afterwards", mgr.isExclusiveBusy());
		mgr.openWallet("B", "pw".toCharArray());
		assertEquals("B", mgr.openWalletId());
	}

	@Test
	public void cacheDirIsWalletSpecificAndDeterministic() {
		String a = XmrWalletManager.dirNameFor("wallet-A");
		String b = XmrWalletManager.dirNameFor("wallet-B");
		assertEquals("same id maps to the same cache dir",
				a, XmrWalletManager.dirNameFor("wallet-A"));
		assertFalse("distinct wallets never share a cache dir", a.equals(b));
		assertFalse("dir name does not leak the raw wallet id",
				a.contains("wallet-A"));
	}

	@Test
	public void openRejectsNonXmrWalletIdFailClosed() {
		store.records.clear();
		store.records.add(new WalletRecord("btcX", WalletCoin.BTC, "wallet", 0,
				true));
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		mgr.openWallet("btcX", "pw".toCharArray());
		assertFalse("a BTC id must not open an XMR session",
				mgr.isSessionValid());
		Event<XmrError> ev = mgr.getError().getValue();
		assertNotNull(ev);
		assertEquals(XmrError.CORRUPTED_ITEM, ev.getIfNotHandled());
	}

	@Test
	public void sendExclusivityIsSingleHolderAndReleases() {
		assertTrue("the send window can take the single exclusive slot",
				mgr.beginExclusive("send"));
		assertTrue(mgr.isExclusiveBusy());
		assertFalse("delete cannot race a held send window",
				mgr.beginExclusive("delete"));
		mgr.endExclusive();
		assertFalse(mgr.isExclusiveBusy());
		assertTrue("after release another exclusive op may begin",
				mgr.beginExclusive("delete"));
		mgr.endExclusive();
	}

	private String journalFor(String walletId) throws Exception {
		return XmrSpendJournal.create(XmrSpendJournal.State.UNCERTAIN, walletId,
				"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
				java.util.Arrays.asList(
						"1111111111111111111111111111111111111111111111111111111111111111"),
				"direct:203.0.113.5:18081", 1L,
				java.util.Collections.emptyList()).serialize();
	}

	@Test
	public void spendQuarantineReflectsTheDurableJournal() throws Exception {
		assertFalse(mgr.isSpendQuarantined("A"));
		store.journals.put("A", journalFor("A"));
		assertTrue("a present journal quarantines", mgr.isSpendQuarantined("A"));
		store.journals.put("A", "corrupt-not-a-journal");
		assertTrue("a corrupt journal still quarantines",
				mgr.isSpendQuarantined("A"));
		store.journals.remove("A");
		assertFalse(mgr.isSpendQuarantined("A"));
	}

	@Test
	public void requireSpendAllowedThrowsWhenQuarantined() throws Exception {
		store.journals.put("A", journalFor("A"));
		try {
			mgr.requireSpendAllowed("A");
			fail("a quarantined wallet must not be allowed to spend");
		} catch (XmrError.XmrException e) {
			assertEquals(XmrError.SPEND_QUARANTINED, e.error);
		}
		store.journals.remove("A");
		mgr.requireSpendAllowed("A");
	}

	@Test
	public void deleteIsBlockedWhileQuarantined() throws Exception {
		store.journals.put("A", journalFor("A"));
		mgr.deleteWallet("A", "pass".toCharArray());
		assertEquals(XmrError.SPEND_QUARANTINED, lastError());
		assertFalse("a quarantined wallet must not be deleted",
				store.deleted.contains("A"));
		assertTrue("the journal survives the blocked delete",
				store.journals.containsKey("A"));
	}

	@Test
	public void renameWhileQuarantinedTouchesNoSpendState() throws Exception {
		store.journals.put("A", journalFor("A"));
		int createdBefore = store.created.size();
		mgr.renameWallet("A", "newname", "pass".toCharArray());
		assertEquals("a rename is presentation-only and never re-creates the "
				+ "item, so a quarantine cannot be affected by it", createdBefore,
				store.created.size());
		assertFalse("a rename never deletes the item", store.deleted.contains("A"));
	}

	@Test
	public void renameUpdatesOnlyPresentationMetadataAndKeepsTheId() {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		int createdBefore = store.created.size();
		mgr.renameWallet("A", "renamed", "pass".toCharArray());
		assertEquals("a rename must not re-create the wallet item (the id and "
				+ "the sealed seed are unchanged)", createdBefore,
				store.created.size());
		assertFalse("a rename must not delete the old item",
				store.deleted.contains("A"));
	}

	private static final String JOURNAL_TXID =
			"1111111111111111111111111111111111111111111111111111111111111111";

	@Test
	public void reconcileClearsQuarantineOnPositiveEvidence() throws Exception {
		store.journals.put("A", journalFor("A"));
		assertTrue(mgr.isSpendQuarantined("A"));
		XmrSpendReconciler.Outcome o = mgr.reconcileSpendJournal("A",
				new java.util.HashSet<>(
						java.util.Collections.singletonList(JOURNAL_TXID)));
		assertEquals(XmrSpendReconciler.Outcome.RESOLVED, o);
		assertFalse("a resolved journal is cleared",
				mgr.isSpendQuarantined("A"));
	}

	@Test
	public void reconcileKeepsQuarantineWithoutPositiveEvidence()
			throws Exception {
		store.journals.put("A", journalFor("A"));
		XmrSpendReconciler.Outcome o = mgr.reconcileSpendJournal("A",
				new java.util.HashSet<>());
		assertEquals(XmrSpendReconciler.Outcome.REMAIN_QUARANTINED, o);
		assertTrue("without positive evidence the wallet stays quarantined",
				mgr.isSpendQuarantined("A"));
	}

	@Test
	public void reconcileNeverClearsACorruptJournal() throws Exception {
		store.journals.put("A", "corrupt-not-a-journal");
		XmrSpendReconciler.Outcome o = mgr.reconcileSpendJournal("A",
				new java.util.HashSet<>(
						java.util.Collections.singletonList(JOURNAL_TXID)));
		assertEquals(XmrSpendReconciler.Outcome.REMAIN_QUARANTINED, o);
		assertTrue("a corrupt journal is never auto-cleared",
				mgr.isSpendQuarantined("A"));
	}

	@Test
	public void prepareSendIsBlockedByQuarantine() throws Exception {
		store.journals.put("A", journalFor("A"));
		mgr.prepareSend("A", "A", "addr", 1000, 0, "pw".toCharArray());
		XmrSendUiState st = mgr.getSendState().getValue();
		assertNotNull(st);
		assertEquals(XmrSendUiState.Kind.QUARANTINED, st.kind);
	}

	@Test
	public void prepareSendWithoutAnOpenSessionFailsClosed() {
		mgr.prepareSend("A", "A", "addr", 1000, 0, "pw".toCharArray());
		XmrSendUiState st = mgr.getSendState().getValue();
		assertNotNull(st);
		assertEquals(XmrSendUiState.Kind.FAILED, st.kind);
		assertEquals(XmrError.SESSION_INVALIDATED, st.error);
	}

	@Test
	public void managerExposesNoRelayOrJournalClearToUi() {
		for (java.lang.reflect.Method m : XmrWalletManager.class.getMethods()) {
			String n = m.getName().toLowerCase();
			assertFalse("no UI-reachable relay: " + m.getName(),
					n.equals("commit") || n.contains("ncommit"));
			assertFalse("no UI-reachable journal clear: " + m.getName(),
					n.contains("removespendjournal")
							|| (n.contains("clear") && n.contains("journal")));
		}
	}

	@Test
	public void sendGuardReflectsSessionState() {
		XmrSendGate.SendGuard g = mgr.sendGuard();
		assertFalse("no valid session before open", g.sessionValid());
		assertTrue("no wallet id before open", g.currentWalletId() == null);
		assertEquals("epoch is -1 with no valid session", -1, g.sessionEpoch());
		assertEquals("lock generation tracks the vault", 5, g.lockGeneration());
	}

	private static final class FakeVaultGate implements VaultGate {
		boolean unlocked = true;
		long generation = 5;
		@Nullable
		Runnable lockListener;

		@Override
		public boolean isUnlocked() {
			return unlocked;
		}

		@Override
		public long getLockGeneration() {
			return generation;
		}

		@Override
		public void addLockListener(Runnable listener) {
			lockListener = listener;
		}

		void fireLock() {
			generation++;
			unlocked = false;
			if (lockListener != null) lockListener.run();
		}
	}

	private static final class Created {
		final WalletCoin coin;
		final boolean hadPassword;

		Created(WalletCoin coin, boolean hadPassword) {
			this.coin = coin;
			this.hadPassword = hadPassword;
		}
	}

	private static final class FakeStore implements XmrStore {
		final List<Created> created = new ArrayList<>();
		final Object monitor = new Object();
		boolean failCreate = false;
		@Nullable
		char[] secret;
		@Nullable
		Exception throwOnLoad;
		@Nullable
		String settings;
		@Nullable
		char[] lastCreatePassword;

		@Override
		public String createWallet(WalletCoin coin, String name, char[] mnemonic,
				@Nullable char[] password) throws Exception {
			if (failCreate) throw new Exception("commit failed");
			lastCreatePassword = password == null ? null : password.clone();
			created.add(new Created(coin,
					password != null && password.length > 0));
			String id = "id-" + created.size();
			records.add(new WalletRecord(id, coin, name, 0, true));
			return id;
		}

		@Override
		public char[] loadMnemonicChars(String walletId,
				@Nullable char[] password) throws Exception {
			if (throwOnLoad != null) throw throwOnLoad;
			return secret == null ? new char[0] : secret.clone();
		}

		final List<WalletRecord> records = new ArrayList<>();

		@Override
		public List<WalletRecord> listWallets() {
			return new ArrayList<>(records);
		}

		final List<String> deleted = new ArrayList<>();
		boolean failDelete = false;

		@Nullable
		Runnable onDelete;

		@Override
		public void deleteWallet(String walletId) throws Exception {
			if (failDelete) throw new java.io.IOException("delete failed");
			if (onDelete != null) onDelete.run();
			deleted.add(walletId);
			records.removeIf(r -> r.id.equals(walletId));
		}

		@Nullable
		@Override
		public String readSettings() {
			return settings;
		}

		@Override
		public void writeSettings(String json) {
			settings = json;
		}

		@Override
		public Object settingsMonitor() {
			return monitor;
		}

		final java.util.Map<String, String> journals =
				new java.util.HashMap<>();
		boolean failJournalWrite = false;

		@Nullable
		@Override
		public String readSpendJournal(String walletId) {
			return journals.get(walletId);
		}

		@Override
		public void writeSpendJournal(String walletId, String journal)
				throws Exception {
			if (failJournalWrite) throw new java.io.IOException("journal write");
			journals.put(walletId, journal);
		}

		@Override
		public void removeSpendJournal(String walletId) {
			journals.remove(walletId);
		}
	}
}

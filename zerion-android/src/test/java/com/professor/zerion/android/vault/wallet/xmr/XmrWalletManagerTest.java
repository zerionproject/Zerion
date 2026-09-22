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

	/** JNI-03: a persisting close whose cache write fails still frees the
	 *  native wallet and invalidates the session; nothing stays open. */
	@Test
	public void closeStillInvalidatesWhenThePersistingCloseFails() {
		mgr.createWallet("w", "pass".toCharArray());
		Event<String> reveal = mgr.getSeedReveal().getValue();
		String id = reveal == null ? null : reveal.getIfNotHandled();
		assertNotNull(id);
		mgr.openWalletForView(id);
		assertTrue(mgr.isSessionValid());
		FakeMoneroEngine.FakeSession bg = engine.lastBackgroundOpened;
		assertNotNull(bg);
		bg.persistFails = true;
		int before = engine.closeCount;
		mgr.closeSession();
		assertFalse(mgr.isSessionValid());
		assertTrue("the native wallet is closed despite the failed store",
				bg.closed);
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

	private static final String NODE =
			"2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion:18089";
	private static final String DEST =
			"42ey1afDFnn4886T7196doS9GPMzexD9gXpsZJDwVjeRVdFCSoHnv7KPbBeGpzJBzHRCAs9UxqeoyFQMYbqSWYTfJJQAWDm";

	private static void awaitTrue(java.util.function.BooleanSupplier c,
			long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!c.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				fail("condition not met within " + timeoutMs + " ms");
			}
			Thread.sleep(20);
		}
	}

	/**
	 * XMR-04: the view-only sync session and the spend-capable relay session
	 * of one wallet must each be initialised with their own SOCKS5 isolation
	 * credential, so a node cannot link syncing to relaying over one circuit.
	 */
	@Test(timeout = 20_000)
	public void syncAndRelaySessionsCarryDistinctIsolationCredentials()
			throws Exception {
		java.util.concurrent.ExecutorService session =
				java.util.concurrent.Executors.newSingleThreadExecutor();
		XmrWalletManager m = new XmrWalletManager(tmpBase, vault, store,
				engine, Runnable::run, session);
		try {
			m.createWallet("w", "pass".toCharArray());
			Event<String> reveal = m.getSeedReveal().getValue();
			String id = reveal == null ? null : reveal.getIfNotHandled();
			assertNotNull(id);
			m.setTorSocksPort(9050);
			m.setSyncNodes(java.util.Collections.singletonList(
					XmrNode.parse(NODE, XmrNode.Source.VETTED, false)));
			m.openWalletForView(id);
			awaitTrue(m::isSessionValid, 10_000);
			FakeMoneroEngine.FakeSession view = engine.lastBackgroundOpened;
			assertNotNull(view);
			awaitTrue(() -> view.lastProxy != null, 10_000);
			assertEquals(XmrTorIsolation.syncProxy(9050, id), view.lastProxy);

			m.prepareSend(id, "w", DEST, 1000, 0, "pass".toCharArray());
			awaitTrue(() -> engine.lastSpendOpened != null
					&& engine.lastSpendOpened.lastProxy != null, 10_000);
			FakeMoneroEngine.FakeSession spend = engine.lastSpendOpened;
			assertEquals(XmrTorIsolation.relayProxy(9050, id), spend.lastProxy);
			assertFalse("relay and sync credentials must differ",
					spend.lastProxy.equals(view.lastProxy));
		} finally {
			m.closeSession();
			session.shutdownNow();
		}
	}

	private static final String T1 =
			"1111111111111111111111111111111111111111111111111111111111111111";

	/** A live manager with a real session thread so the sync loop and the send
	 *  flow run as in production; the test observes through LiveData. */
	private final class Live implements AutoCloseable {
		final java.util.concurrent.ExecutorService session =
				java.util.concurrent.Executors.newSingleThreadExecutor();
		final XmrWalletManager m;
		final java.util.concurrent.atomic.AtomicLong clock =
				new java.util.concurrent.atomic.AtomicLong(1_000_000L);
		String id;

		Live() {
			m = new XmrWalletManager(tmpBase, vault, store, engine,
					Runnable::run, session);
			m.setSendClock(clock::get);
		}

		void openView() throws Exception {
			m.createWallet("w", "pass".toCharArray());
			Event<String> reveal = m.getSeedReveal().getValue();
			id = reveal == null ? null : reveal.getIfNotHandled();
			assertNotNull(id);
			m.setTorSocksPort(9050);
			m.setSyncNodes(java.util.Collections.singletonList(
					XmrNode.parse(NODE, XmrNode.Source.VETTED, false)));
			m.openWalletForView(id);
			awaitTrue(m::isSessionValid, 10_000);
		}

		FakeMoneroEngine.FakePrepared reachReview() throws Exception {
			openView();
			FakeMoneroEngine.FakePrepared p = new FakeMoneroEngine.FakePrepared();
			p.ids.add(T1);
			p.count = 1;
			p.amount = 1_000_000_000_000L;
			p.fee = 30_000_000L;
			p.dust = 0;
			p.change = 500_000_000L;
			engine.preparedForNewSessions = p;
			m.prepareSend(id, "w", DEST, p.amount, 0, "pass".toCharArray());
			awaitTrue(() -> kind() == XmrSendUiState.Kind.REVIEW, 10_000);
			return p;
		}

		@Nullable
		XmrSendUiState.Kind kind() {
			XmrSendUiState st = m.getSendState().getValue();
			return st == null ? null : st.kind;
		}

		@Override
		public void close() {
			m.closeSession();
			session.shutdownNow();
		}
	}

	/** XMR-03: a review nobody answers must not hold the spend session, the
	 *  signed transaction and the exclusive slot for the process lifetime. */
	@Test(timeout = 20_000)
	public void orphanedReviewIsReleasedByTheWatchdog() throws Exception {
		try (Live live = new Live()) {
			FakeMoneroEngine.FakePrepared p = live.reachReview();
			assertTrue("review holds the exclusive slot", live.m.isExclusiveBusy());
			assertNotNull(engine.lastSpendOpened);
			assertFalse("spend session stays open at review",
					engine.lastSpendOpened.closed);

			live.m.expireStaleSendFlow();
			assertTrue("the watchdog must not fire before the TTL",
					live.m.isExclusiveBusy());

			live.clock.addAndGet(XmrWalletManager.SPEND_SESSION_TTL_MS);
			live.m.expireStaleSendFlow();
			awaitTrue(() -> !live.m.isExclusiveBusy(), 10_000);
			awaitTrue(() -> engine.lastSpendOpened.closed, 10_000);
			assertTrue("the signed transaction is freed", p.disposed);
			assertEquals(XmrSendUiState.Kind.CANCELLED, live.kind());

			live.m.confirmSend("pass".toCharArray());
			awaitTrue(() -> live.kind() == XmrSendUiState.Kind.FAILED, 10_000);
			assertEquals("a cancelled flow never relays", 0, p.commits);
		}
	}

	/** XMR-03: while a review is orphaned every other XMR wallet is blocked. */
	@Test(timeout = 20_000)
	public void orphanedReviewBlocksOtherWalletsUntilReleased() throws Exception {
		try (Live live = new Live()) {
			live.reachReview();
			live.m.openWalletForView("A");
			awaitTrue(() -> {
				Event<XmrError> e = live.m.getError().getValue();
				return e != null && !e.isHandled();
			}, 10_000);
			Event<XmrError> busy = live.m.getError().getValue();
			assertNotNull(busy);
			assertEquals(XmrError.BUSY, busy.getIfNotHandled());
			live.clock.addAndGet(XmrWalletManager.SPEND_SESSION_TTL_MS);
			live.m.expireStaleSendFlow();
			awaitTrue(() -> !live.m.isExclusiveBusy(), 10_000);
		}
	}

	/** XMR-03: an explicit session close tears down an active flow. */
	@Test(timeout = 20_000)
	public void explicitCloseTearsDownAnActiveReview() throws Exception {
		Live live = new Live();
		try {
			FakeMoneroEngine.FakePrepared p = live.reachReview();
			live.m.closeSession();
			awaitTrue(() -> !live.m.isExclusiveBusy(), 10_000);
			awaitTrue(() -> engine.lastSpendOpened.closed, 10_000);
			assertTrue(p.disposed);
		} finally {
			live.session.shutdownNow();
		}
	}

	/** XMR-03: the vault lock always wins over an active review. */
	@Test(timeout = 20_000)
	public void vaultLockDestroysSpendCapabilityDuringReview() throws Exception {
		try (Live live = new Live()) {
			FakeMoneroEngine.FakePrepared p = live.reachReview();
			vault.fireLock();
			awaitTrue(() -> !live.m.isExclusiveBusy(), 10_000);
			awaitTrue(() -> engine.lastSpendOpened.closed, 10_000);
			assertTrue("lock frees the signed transaction", p.disposed);
			live.m.confirmSend("pass".toCharArray());
			awaitTrue(() -> live.kind() == XmrSendUiState.Kind.FAILED, 10_000);
			assertEquals("no relay after lock", 0, p.commits);
		}
	}

	/** XMR-05: an uncertain relay must keep the balance reservation, durably,
	 *  because wallet2 marks inputs spent only after the daemon accepted the
	 *  transaction and the funds may nevertheless be gone. */
	@Test(timeout = 20_000)
	public void uncertainRelayKeepsTheReservationAcrossRestart()
			throws Exception {
		String id;
		try (Live live = new Live()) {
			FakeMoneroEngine.FakePrepared p = live.reachReview();
			p.commitResult = false;
			live.m.confirmSend("pass".toCharArray());
			awaitTrue(() -> live.kind() == XmrSendUiState.Kind.RELAY_UNCERTAIN,
					10_000);
			assertEquals("relayed exactly once", 1, p.commits);
			id = live.id;
			List<XmrPendingSend> pending = live.m.pendingSendsFor(id);
			assertEquals(1, pending.size());
			XmrPendingSend r = pending.get(0);
			assertEquals(XmrPendingSend.ReservationState.RELAY_UNCERTAIN,
					r.reservationState());
			assertFalse("never converged on the uncertain path", r.converged);
			assertEquals("the consumed inputs stay reserved",
					p.amount + p.fee + p.change, r.reservationDebit());
			assertTrue("the journal quarantines the wallet",
					live.m.isSpendQuarantined(id));
		}
		XmrWalletManager restarted = new XmrWalletManager(tmpBase, vault, store,
				engine, Runnable::run);
		List<XmrPendingSend> after = restarted.pendingSendsFor(id);
		assertEquals(1, after.size());
		assertEquals(XmrPendingSend.ReservationState.RELAY_UNCERTAIN,
				after.get(0).reservationState());
		assertTrue(after.get(0).reservationDebit() > 0);
		assertTrue(restarted.isSpendQuarantined(id));
	}

	/** XMR-05 contrast: an accepted relay converges and releases exactly once. */
	@Test(timeout = 20_000)
	public void acceptedRelayConvergesAndReleasesTheReservation()
			throws Exception {
		try (Live live = new Live()) {
			FakeMoneroEngine.FakePrepared p = live.reachReview();
			live.m.confirmSend("pass".toCharArray());
			awaitTrue(() -> live.kind() == XmrSendUiState.Kind.SUCCESS, 10_000);
			assertEquals(1, p.commits);
			List<XmrPendingSend> pending = live.m.pendingSendsFor(live.id);
			assertEquals(1, pending.size());
			assertEquals(XmrPendingSend.ReservationState.CONVERGED,
					pending.get(0).reservationState());
			assertEquals(0, pending.get(0).reservationDebit());
		}
	}

	private static final long MINED = 2_500_000L;
	private static final long DAY_MS = 24L * 60 * 60 * 1000;

	/** XMR-07: the spend wallet opened for a later send is the first place
	 *  a spend the view-only cache cannot see becomes visible; a reservation
	 *  it reports as spent converges there, and the view is rebuilt from the
	 *  spend wallet's state when the send is abandoned. */
	@Test(timeout = 30_000)
	public void laterSendConvergesAnObservedSpendAndRebuildsTheView()
			throws Exception {
		try (Live live = new Live()) {
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_MISSED};
			relay(live, false, XmrSendUiState.Kind.RELAY_UNCERTAIN);
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_IN_POOL};
			live.m.refreshNow();
			awaitTrue(() -> !live.m.isSpendQuarantined(live.id), 10_000);
			assertEquals(XmrPendingSend.ReservationState.RELAY_UNCERTAIN,
					live.m.pendingSendsFor(live.id).get(0).reservationState());
			assertTrue(live.m.pendingSendsFor(live.id).get(0)
					.reservationDebit() > 0);
			FakeMoneroEngine.FakeSession firstView = engine.lastBackgroundOpened;
			assertNotNull(firstView);

			engine.spendOutgoingForNewSessions = java.util.Collections
					.singletonList(XmrTxInfo.parse(T1 + ",1,1000000000000,"
							+ "30000000,3750000,1700000500,6,0,0,0"));
			FakeMoneroEngine.FakePrepared p2 = new FakeMoneroEngine.FakePrepared();
			p2.ids.add("2222222222222222222222222222222222222222222222222222222222222222");
			p2.count = 1;
			p2.amount = 1_000_000L;
			p2.fee = 30_000_000L;
			p2.change = 0;
			engine.preparedForNewSessions = p2;
			live.m.prepareSend(live.id, "w", DEST, p2.amount, 0,
					"pass".toCharArray());
			awaitTrue(() -> live.kind() == XmrSendUiState.Kind.REVIEW, 10_000);
			assertEquals("the spend wallet's report converges the send",
					XmrPendingSend.ReservationState.CONVERGED,
					live.m.pendingSendsFor(live.id).get(0).reservationState());
			assertEquals(0, live.m.pendingSendsFor(live.id).get(0)
					.reservationDebit());
			FakeMoneroEngine.FakeSession spend = engine.lastSpendOpened;
			assertNotNull(spend);
			int storesBefore = spend.storeCalls;

			live.m.cancelSend();
			awaitTrue(() -> live.kind() == XmrSendUiState.Kind.CANCELLED,
					10_000);
			awaitTrue(() -> engine.lastBackgroundOpened != firstView, 10_000);
			assertTrue("the spend state was written to the cache",
					spend.storeCalls > storesBefore);
			assertTrue("the old view is closed", firstView.closed);
			awaitTrue(live.m::isSessionValid, 10_000);
			assertFalse("the rebuilt view is live",
					engine.lastBackgroundOpened.closed);
			assertFalse(live.m.isExclusiveBusy());
			assertTrue(p2.disposed);
		}
	}

	/** XMR-10: a wallet created while the clock ran ahead persists a restore
	 *  height above the chain tip; the first daemon height caps it below the
	 *  tip and rescans, once, and the marker never survives the correction. */
	@Test(timeout = 30_000)
	public void clockAheadRestoreHeightIsCappedByTheFirstDaemonHeight()
			throws Exception {
		long daemon = 1_000_000L;
		long cap = daemon - XmrBirthday.BASE_MARGIN_BLOCKS;
		engine.daemonHeightForNewSessions = daemon;
		try (Live live = new Live()) {
			live.openView();
			assertTrue("the clock estimate is far above the fake tip",
					XmrBirthday.estimateHeight(System.currentTimeMillis())
							> daemon);
			FakeMoneroEngine.FakeSession view = engine.lastBackgroundOpened;
			assertNotNull(view);
			awaitTrue(() -> view.rescanCalls.get() > 0, 10_000);
			assertEquals(cap, view.refreshFromHeight);
			assertTrue(view.refreshLog.contains("rescan@" + cap));
			org.json.JSONObject w = new org.json.JSONObject(store.settings)
					.getJSONObject("xmr").getJSONObject(live.id);
			assertEquals(cap, w.getLong("h"));
			assertFalse("the clock marker is consumed", w.has("hEst"));
			Thread.sleep(700);
			assertEquals("the correction runs once", 1, view.rescanCalls.get());
		}
	}

	/** XMR-10 contrast: a height the user chose is never capped. */
	@Test(timeout = 30_000)
	public void importedRestoreHeightIsNeverCappedByTheDaemon()
			throws Exception {
		engine.daemonHeightForNewSessions = 1_000_000L;
		try (Live live = new Live()) {
			live.m.importWallet("w", FakeMoneroEngine.FAKE_SEED.toCharArray(),
					3_000_000L, "pass".toCharArray());
			String id = null;
			for (com.professor.zerion.android.vault.wallet.WalletRecord r
					: store.records) {
				if (r.id.startsWith("id-")) id = r.id;
			}
			assertNotNull(id);
			openExisting(live, id);
			awaitTrue(() -> live.m.getSyncStatus().getValue() != null
					&& live.m.getSyncStatus().getValue().daemonHeight
					== 1_000_000L, 10_000);
			Thread.sleep(700);
			FakeMoneroEngine.FakeSession view = engine.lastBackgroundOpened;
			assertNotNull(view);
			assertEquals(0, view.rescanCalls.get());
			org.json.JSONObject w = new org.json.JSONObject(store.settings)
					.getJSONObject("xmr").getJSONObject(id);
			assertEquals(3_000_000L, w.getLong("h"));
		}
	}

	private void openExisting(Live live, String id) throws Exception {
		live.id = id;
		live.m.setTorSocksPort(9050);
		live.m.setSyncNodes(java.util.Collections.singletonList(
				XmrNode.parse(NODE, XmrNode.Source.VETTED, false)));
		live.m.openWalletForView(id);
		awaitTrue(live.m::isSessionValid, 10_000);
	}

	/** Drives one send to a terminal relay state and returns the prepared tx. */
	private FakeMoneroEngine.FakePrepared relay(Live live, boolean accepted,
			XmrSendUiState.Kind expected) throws Exception {
		FakeMoneroEngine.FakePrepared p = live.reachReview();
		p.commitResult = accepted;
		live.m.confirmSend("pass".toCharArray());
		awaitTrue(() -> live.kind() == expected, 10_000);
		return p;
	}

	/** XMR-01: an accepted relay the daemon can see resolves at once. */
	@Test(timeout = 20_000)
	public void relaySuccessResolvesTheJournalFromTheDaemon() throws Exception {
		try (Live live = new Live()) {
			engine.lookupCodes = new long[] {MINED};
			relay(live, true, XmrSendUiState.Kind.SUCCESS);
			assertFalse("positive daemon evidence clears the journal",
					live.m.isSpendQuarantined(live.id));
		}
	}

	/** XMR-01: a daemon rejection is uncertain, never auto-released: the node
	 *  may have broadcast and lied, so only positive evidence or the expiry
	 *  gated release may end it. */
	@Test(timeout = 20_000)
	public void daemonRejectionStaysUncertainAndIsNeverAutoReleased()
			throws Exception {
		try (Live live = new Live()) {
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_MISSED};
			relay(live, false, XmrSendUiState.Kind.RELAY_UNCERTAIN);
			assertTrue(live.m.isSpendQuarantined(live.id));
			for (int i = 0; i < 3; i++) {
				live.m.refreshNow();
				Thread.sleep(300);
			}
			assertTrue("MISSED answers never resolve anything",
					live.m.isSpendQuarantined(live.id));
			assertEquals(XmrPendingSend.ReservationState.RELAY_UNCERTAIN,
					live.m.pendingSendsFor(live.id).get(0).reservationState());
		}
	}

	/** XMR-01: a timeout after submission resolves when the daemon later
	 *  reports the transaction; the reservation is held until convergence. */
	@Test(timeout = 20_000)
	public void timeoutAfterSubmissionResolvesWhenTheDaemonSeesIt()
			throws Exception {
		try (Live live = new Live()) {
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_MISSED};
			relay(live, false, XmrSendUiState.Kind.RELAY_UNCERTAIN);
			assertTrue(live.m.isSpendQuarantined(live.id));
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_IN_POOL};
			live.m.refreshNow();
			awaitTrue(() -> !live.m.isSpendQuarantined(live.id), 10_000);
			assertEquals("the reservation outlives the quarantine",
					XmrPendingSend.ReservationState.RELAY_UNCERTAIN,
					live.m.pendingSendsFor(live.id).get(0).reservationState());
		}
	}

	/** XMR-01: losing the connection before submission leaves no journal. */
	@Test(timeout = 20_000)
	public void connectionLossBeforeSubmissionLeavesNoJournal()
			throws Exception {
		try (Live live = new Live()) {
			live.openView();
			engine.failSpendInit = true;
			try {
				live.m.prepareSend(live.id, "w", DEST, 1000, 0,
						"pass".toCharArray());
				awaitTrue(() -> live.kind() == XmrSendUiState.Kind.FAILED,
						10_000);
			} finally {
				engine.failSpendInit = false;
			}
			assertFalse(live.m.isSpendQuarantined(live.id));
			assertTrue(live.m.pendingSendsFor(live.id).isEmpty());
		}
	}

	/** XMR-01: a commit that throws after the journal is durable is uncertain,
	 *  not a plain failure, so it can never be silently forgotten. */
	@Test(timeout = 20_000)
	public void connectionLossAfterPossibleSubmissionIsUncertain()
			throws Exception {
		try (Live live = new Live()) {
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_MISSED};
			FakeMoneroEngine.FakePrepared p = live.reachReview();
			p.commitThrows = true;
			live.m.confirmSend("pass".toCharArray());
			awaitTrue(() -> live.kind() == XmrSendUiState.Kind.RELAY_UNCERTAIN,
					10_000);
			assertTrue(live.m.isSpendQuarantined(live.id));
			assertEquals(1, live.m.pendingSendsFor(live.id).size());
		}
	}

	/** XMR-01: restart while uncertain, then the view open reconciles. */
	@Test(timeout = 30_000)
	public void restartWhileUncertainReconcilesOnOpen() throws Exception {
		String id;
		try (Live live = new Live()) {
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_MISSED};
			relay(live, false, XmrSendUiState.Kind.RELAY_UNCERTAIN);
			id = live.id;
		}
		assertTrue(new XmrWalletManager(tmpBase, vault, store, engine,
				Runnable::run).isSpendQuarantined(id));
		engine.lookupCodes = new long[] {MINED};
		try (Live again = new Live()) {
			openExisting(again, id);
			awaitTrue(() -> !again.m.isSpendQuarantined(id), 10_000);
		}
	}

	/** XMR-01: after a node switch reconciliation runs against the new daemon. */
	@Test(timeout = 30_000)
	public void nodeSwitchWhileUncertainStillReconciles() throws Exception {
		String id;
		try (Live live = new Live()) {
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_MISSED};
			relay(live, false, XmrSendUiState.Kind.RELAY_UNCERTAIN);
			id = live.id;
		}
		engine.lookupCodes = new long[] {MINED};
		try (Live again = new Live()) {
			again.id = id;
			again.m.setTorSocksPort(9050);
			again.m.setSyncNodes(java.util.Collections.singletonList(
					XmrNode.parse("4iv75ceaj2xjqne6d5d35xxk7lkcj6zdtpsbp7sq6sobp44b7txqrcid.onion:18089",
							XmrNode.Source.VETTED, false)));
			again.m.openWalletForView(id);
			awaitTrue(again.m::isSessionValid, 10_000);
			awaitTrue(() -> !again.m.isSpendQuarantined(id), 10_000);
		}
	}

	/** XMR-01: a transaction that never appears stays quarantined until the
	 *  password-gated, expiry-gated release; the release drops the reservation. */
	@Test(timeout = 30_000)
	public void transactionNeverAppearsIsReleasableOnlyAfterExpiry()
			throws Exception {
		try (Live live = new Live()) {
			java.util.concurrent.atomic.AtomicLong wall =
					new java.util.concurrent.atomic.AtomicLong(
							System.currentTimeMillis());
			live.m.setWallClock(wall::get);
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_MISSED};
			relay(live, false, XmrSendUiState.Kind.RELAY_UNCERTAIN);

			live.m.releaseUnresolvedSend(live.id, "pass".toCharArray());
			awaitTrue(() -> {
				Event<XmrError> e = live.m.getError().getValue();
				return e != null && !e.isHandled();
			}, 10_000);
			assertEquals(XmrError.RELAY_UNRESOLVED,
					live.m.getError().getValue().getIfNotHandled());
			assertTrue("too early: still quarantined",
					live.m.isSpendQuarantined(live.id));

			wall.addAndGet(3 * DAY_MS + 1000);
			live.m.releaseUnresolvedSend(live.id, "pass".toCharArray());
			awaitTrue(() -> {
				Event<String> e = live.m.getSpendReleased().getValue();
				return e != null && !e.isHandled();
			}, 10_000);
			assertFalse(live.m.isSpendQuarantined(live.id));
			assertTrue("release drops the reservation",
					live.m.pendingSendsFor(live.id).isEmpty());
		}
	}

	/** XMR-01: the release is refused when the daemon did not answer. */
	@Test(timeout = 30_000)
	public void releaseIsRefusedWithoutAnAnsweringDaemon() throws Exception {
		try (Live live = new Live()) {
			java.util.concurrent.atomic.AtomicLong wall =
					new java.util.concurrent.atomic.AtomicLong(
							System.currentTimeMillis());
			live.m.setWallClock(wall::get);
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_MISSED};
			relay(live, false, XmrSendUiState.Kind.RELAY_UNCERTAIN);
			wall.addAndGet(3 * DAY_MS + 1000);
			engine.lookupCodes = null;
			live.m.releaseUnresolvedSend(live.id, "pass".toCharArray());
			awaitTrue(() -> {
				Event<XmrError> e = live.m.getError().getValue();
				return e != null && !e.isHandled();
			}, 10_000);
			assertEquals(XmrError.RELAY_UNRESOLVED,
					live.m.getError().getValue().getIfNotHandled());
			assertTrue(live.m.isSpendQuarantined(live.id));
		}
	}

	/** XMR-01: a wrong password never releases anything. */
	@Test(timeout = 30_000)
	public void releaseRequiresTheWalletPassword() throws Exception {
		try (Live live = new Live()) {
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_MISSED};
			relay(live, false, XmrSendUiState.Kind.RELAY_UNCERTAIN);
			store.throwOnLoad = new javax.crypto.AEADBadTagException("bad");
			try {
				live.m.releaseUnresolvedSend(live.id, "wrong".toCharArray());
				awaitTrue(() -> {
					Event<XmrError> e = live.m.getError().getValue();
					return e != null && !e.isHandled();
				}, 10_000);
				assertEquals(XmrError.WRONG_PASSWORD,
						live.m.getError().getValue().getIfNotHandled());
			} finally {
				store.throwOnLoad = null;
			}
			assertTrue(live.m.isSpendQuarantined(live.id));
		}
	}

	/** XMR-01: reconciliation is idempotent under repetition. */
	@Test(timeout = 30_000)
	public void repeatedReconciliationIsIdempotent() throws Exception {
		try (Live live = new Live()) {
			engine.lookupCodes = new long[] {XmrTxLookup.CODE_MISSED};
			relay(live, false, XmrSendUiState.Kind.RELAY_UNCERTAIN);
			for (int i = 0; i < 5; i++) live.m.refreshNow();
			Thread.sleep(500);
			assertTrue(live.m.isSpendQuarantined(live.id));
			engine.lookupCodes = new long[] {MINED};
			for (int i = 0; i < 3; i++) live.m.refreshNow();
			awaitTrue(() -> !live.m.isSpendQuarantined(live.id), 10_000);
			for (int i = 0; i < 3; i++) live.m.refreshNow();
			Thread.sleep(300);
			assertFalse(live.m.isSpendQuarantined(live.id));
			Event<XmrError> e = live.m.getError().getValue();
			assertTrue("no error is raised by repeated reconciliation",
					e == null || e.isHandled());
		}
	}

	/** XMR-01: a quarantined wallet is deletable only with the acknowledgement. */
	@Test
	public void deleteWhileQuarantinedRequiresAcknowledgement() throws Exception {
		store.secret = FakeMoneroEngine.FAKE_SEED.toCharArray();
		store.journals.put("A", journalFor("A"));
		mgr.deleteWallet("A", "pass".toCharArray());
		assertEquals(XmrError.SPEND_QUARANTINED, lastError());
		assertFalse(store.deleted.contains("A"));
		mgr.deleteWallet("A", "pass".toCharArray(), true);
		assertTrue("acknowledged delete proceeds", store.deleted.contains("A"));
		assertFalse("the journal is removed with the wallet",
				store.journals.containsKey("A"));
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

		private final java.util.Map<String, byte[]> walletSecrets =
				new java.util.HashMap<>();

		@Override
		@Nullable
		public byte[] readWalletSecret(String walletId, String name) {
			byte[] v = walletSecrets.get(walletId + "/" + name);
			return v == null ? null : v.clone();
		}

		@Override
		public void writeWalletSecret(String walletId, String name,
				byte[] value) {
			walletSecrets.put(walletId + "/" + name, value.clone());
		}

		@Override
		public void removeWalletSecret(String walletId, String name) {
			walletSecrets.remove(walletId + "/" + name);
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

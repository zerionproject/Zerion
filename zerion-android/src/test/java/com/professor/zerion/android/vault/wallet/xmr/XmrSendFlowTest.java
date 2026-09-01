package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.Nullable;

import com.professor.zerion.android.vault.wallet.WalletCoin;
import com.professor.zerion.android.vault.wallet.WalletRecord;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The send state machine drives a transaction from input to relay with the
 * exact ordering the safety model requires: quarantine and validity checks
 * before construction, refresh quiesced before signing, fresh authorization
 * after the snapshot, and on one operation the ownership-checked validation, the
 * durable journal write, and only then the single commit. A journal write
 * failure means no commit; an uncertain commit leaves a durable quarantine; a
 * mutation or a stale authorization fails closed.
 */
public class XmrSendFlowTest {

	private static final String WALLET = "w1";
	private static final String DEST =
			"42ey1afDFnn4886T7196doS9GPMzexD9gXpsZJDwVjeRVdFCSoHnv7KPbBeGpzJBzHRCAs9UxqeoyFQMYbqSWYTfJJQAWDm";
	private static final String T1 =
			"1111111111111111111111111111111111111111111111111111111111111111";
	private static final String T2 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String EP = "direct:203.0.113.5:18081";

	private FakeMoneroEngine engine;
	private FakeMoneroEngine.FakeSession session;
	private FakeStore store;
	private XmrSpendJournalStore journalStore;
	private FakeGuard guard;
	private Clock clock;
	private XmrSendGate gate;
	private Set<String> history;

	@Before
	public void setUp() {
		engine = new FakeMoneroEngine();
		session = (FakeMoneroEngine.FakeSession)
				engine.create("w", "pw".toCharArray(), "English");
		engine.refreshIdle = true;
		store = new FakeStore();
		store.password = "pw".toCharArray();
		journalStore = new XmrSpendJournalStore(store);
		guard = new FakeGuard(5, 5, true, WALLET);
		clock = new Clock();
		gate = new XmrSendGate(store, guard, clock, 60_000);
		history = new HashSet<>();
	}

	private static byte[] fp32() {
		byte[] b = new byte[32];
		for (int i = 0; i < 32; i++) b[i] = (byte) (i + 1);
		return b;
	}

	private FakeMoneroEngine.FakePrepared prepared(String... ids) {
		FakeMoneroEngine.FakePrepared p = new FakeMoneroEngine.FakePrepared();
		p.ids.addAll(java.util.Arrays.asList(ids));
		p.count = ids.length;
		p.amount = 1_000_000_000_000L;
		p.fee = 30_000_000L;
		p.dust = 5_000L;
		return p;
	}

	private XmrSendFlow flow() {
		return new XmrSendFlow(engine, session, 0, WALLET, gate, journalStore,
				guard, () -> EP, () -> history, 1000);
	}

	private void prepareOk(XmrSendFlow f, FakeMoneroEngine.FakePrepared p)
			throws Exception {
		session.nextPrepared = p;
		f.prepare(DEST, 1_000_000_000_000L, 0, fp32());
	}

	@Test
	public void happyPathRelaysOnceAndResolvesTheJournal() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendFlow f = flow();
		prepareOk(f, p);
		assertEquals(XmrSendFlow.State.REVIEW_READY, f.state());
		XmrSendSnapshot s = f.snapshot();
		assertNotNull(s);
		assertEquals(1_000_030_000_000L, s.totalDebitAtomic());

		f.authorize("pw".toCharArray());
		assertEquals(XmrSendFlow.State.AUTHORIZED, f.state());

		history.add(T1);
		assertEquals(XmrSendFlow.RelayResult.SUCCESS, f.confirmAndRelay());
		assertEquals(XmrSendFlow.State.SUCCESS, f.state());
		assertEquals("the transaction is relayed exactly once", 1, p.commits);
		assertTrue("the prepared transaction is disposed", p.disposed);
		assertFalse("a fully accepted send resolves the quarantine",
				journalStore.isQuarantined(WALLET));
	}

	@Test
	public void quarantinedWalletCannotPrepare() throws Exception {
		store.journals.put(WALLET, journalString());
		XmrSendFlow f = flow();
		session.nextPrepared = prepared(T1);
		try {
			f.prepare(DEST, 1_000_000_000_000L, 0, fp32());
			fail("a quarantined wallet must not prepare");
		} catch (XmrError.XmrException e) {
			assertEquals(XmrError.SPEND_QUARANTINED, e.error);
		}
		assertEquals(0, session.prepareCalls);
	}

	@Test
	public void refreshNotIdleFailsBusyAndResumes() throws Exception {
		engine.refreshIdle = false;
		XmrSendFlow f = flow();
		session.nextPrepared = prepared(T1);
		try {
			f.prepare(DEST, 1_000_000_000_000L, 0, fp32());
			fail("a wallet that will not quiesce is busy");
		} catch (XmrError.XmrException e) {
			assertEquals(XmrError.BUSY, e.error);
		}
		assertEquals(0, session.prepareCalls);
	}

	@Test
	public void zeroAmountAndBadAddressFailClosed() {
		XmrSendFlow f1 = flow();
		expect(XmrError.SEND_SNAPSHOT_INVALID,
				() -> f1.prepare(DEST, 0, 0, fp32()));
		XmrSendFlow f2 = flow();
		expect(XmrError.SEND_SNAPSHOT_INVALID,
				() -> f2.prepare("garbage", 1000, 0, fp32()));
	}

	@Test
	public void journalWriteFailureMeansNoRelay() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendFlow f = flow();
		prepareOk(f, p);
		f.authorize("pw".toCharArray());
		store.failJournalWrite = true;
		assertEquals(XmrSendFlow.RelayResult.FAILED, f.confirmAndRelay());
		assertEquals("no commit may happen when the journal write fails", 0,
				p.commits);
		assertEquals(XmrSendFlow.State.FAILED, f.state());
	}

	@Test
	public void uncertainCommitLeavesADurableQuarantine() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		p.commitResult = false;
		XmrSendFlow f = flow();
		prepareOk(f, p);
		f.authorize("pw".toCharArray());
		assertEquals("a commit that returns false is uncertain",
				XmrSendFlow.RelayResult.RELAY_UNCERTAIN, f.confirmAndRelay());
		assertEquals(XmrSendFlow.State.RELAY_UNCERTAIN, f.state());
		assertTrue("an uncertain relay stays quarantined until reconciled",
				journalStore.isQuarantined(WALLET));
	}

	@Test
	public void mutationBetweenAuthAndRelayFailsClosed() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendFlow f = flow();
		prepareOk(f, p);
		f.authorize("pw".toCharArray());
		p.ids.set(0, T2);
		assertEquals(XmrSendFlow.RelayResult.FAILED, f.confirmAndRelay());
		assertEquals(0, p.commits);
		assertFalse("a rejected relay wrote no journal",
				journalStore.isQuarantined(WALLET));
	}

	@Test
	public void lockAfterAuthorizationBlocksRelay() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendFlow f = flow();
		prepareOk(f, p);
		f.authorize("pw".toCharArray());
		guard.valid = false;
		guard.lockGeneration = 6;
		assertEquals(XmrSendFlow.RelayResult.FAILED, f.confirmAndRelay());
		assertEquals(0, p.commits);
	}

	@Test
	public void cancelDisposesAndInvalidates() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendFlow f = flow();
		prepareOk(f, p);
		f.authorize("pw".toCharArray());
		XmrAuthToken tok = gate.activeToken();
		assertNotNull(tok);
		f.cancel();
		assertEquals(XmrSendFlow.State.CANCELLED, f.state());
		assertTrue(p.disposed);
		assertTrue(tok.isInvalidated());
		assertEquals(0, p.commits);
	}

	@Test
	public void invalidateKillsTokenWithoutTouchingNativeOffExecutor()
			throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendFlow f = flow();
		prepareOk(f, p);
		f.authorize("pw".toCharArray());
		XmrAuthToken tok = gate.activeToken();
		assertNotNull(tok);

		f.invalidate();

		assertEquals(XmrSendFlow.State.CANCELLED, f.state());
		assertTrue("the authorization is killed at once", tok.isInvalidated());
		assertFalse("invalidate must not free the native transaction "
				+ "off the executor", p.disposed);
		assertEquals(0, p.closes);

		assertEquals("no relay can begin after invalidate",
				XmrSendFlow.RelayResult.FAILED, f.confirmAndRelay());
		assertEquals(0, p.commits);

		f.disposeOnExecutor();
		assertTrue("the native transaction is freed on the executor",
				p.disposed);
		assertEquals(1, p.closes);
		f.disposeOnExecutor();
		assertEquals("disposal on the executor is idempotent", 1, p.closes);
	}

	@Test
	public void stageAaloneCannotRelayWithoutPostReviewAuthorization()
			throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendFlow f = flow();
		prepareOk(f, p);
		assertEquals("after construction the flow waits at review, not authorized",
				XmrSendFlow.State.REVIEW_READY, f.state());
		assertEquals("a prepared but unauthorized transaction never relays",
				XmrSendFlow.RelayResult.FAILED, f.confirmAndRelay());
		assertEquals(0, p.commits);
		assertEquals("the prepared transaction survives for a proper "
				+ "post-review authorization", XmrSendFlow.State.REVIEW_READY,
				f.state());
	}

	@Test
	public void doubleConfirmRelaysAtMostOnce() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendFlow f = flow();
		prepareOk(f, p);
		f.authorize("pw".toCharArray());
		history.add(T1);
		assertEquals(XmrSendFlow.RelayResult.SUCCESS, f.confirmAndRelay());
		assertEquals("a second confirm cannot relay again",
				XmrSendFlow.RelayResult.FAILED, f.confirmAndRelay());
		assertEquals("the transaction is relayed exactly once", 1, p.commits);
	}

	@Test
	public void badPasswordStaysAtReviewReady() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendFlow f = flow();
		prepareOk(f, p);
		try {
			f.authorize("wrong".toCharArray());
			fail("a wrong password must not authorize");
		} catch (XmrError.XmrException e) {
			assertEquals(XmrError.WRONG_PASSWORD, e.error);
		}
		assertEquals(XmrSendFlow.State.REVIEW_READY, f.state());
	}

	private static String journalString() throws XmrError.XmrException {
		return XmrSpendJournal.create(XmrSpendJournal.State.UNCERTAIN, WALLET,
				"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
				Collections.singletonList(T1), EP, 1L,
				Collections.emptyList()).serialize();
	}

	private interface Run {
		void run() throws XmrError.XmrException;
	}

	private static void expect(XmrError expected, Run r) {
		try {
			r.run();
			fail("expected " + expected);
		} catch (XmrError.XmrException e) {
			assertEquals(expected, e.error);
		}
	}

	private static final class Clock implements XmrSendGate.MonotonicClock {
		long now = 10_000;

		@Override
		public long nowMonotonicMs() {
			return now;
		}
	}

	private static final class FakeGuard implements XmrSendGate.SendGuard {
		long sessionEpoch;
		long lockGeneration;
		boolean valid;
		@Nullable
		String walletId;

		FakeGuard(long e, long l, boolean v, @Nullable String w) {
			sessionEpoch = e;
			lockGeneration = l;
			valid = v;
			walletId = w;
		}

		@Override
		public long sessionEpoch() {
			return sessionEpoch;
		}

		@Override
		public long lockGeneration() {
			return lockGeneration;
		}

		@Override
		public boolean sessionValid() {
			return valid;
		}

		@Nullable
		@Override
		public String currentWalletId() {
			return walletId;
		}
	}

	private static final class FakeStore implements XmrStore {
		@Nullable
		char[] password;
		final Map<String, String> journals = new java.util.HashMap<>();
		boolean failJournalWrite = false;

		@Override
		public char[] loadMnemonicChars(String walletId,
				@Nullable char[] password) throws Exception {
			if (password == null || password.length == 0) {
				throw new SecurityException("required");
			}
			if (this.password == null
					|| !java.util.Arrays.equals(password, this.password)) {
				throw new javax.crypto.AEADBadTagException("bad");
			}
			return "abandon ability able".toCharArray();
		}

		@Nullable
		@Override
		public String readSpendJournal(String walletId) {
			return journals.get(walletId);
		}

		@Override
		public void writeSpendJournal(String walletId, String journal)
				throws Exception {
			if (failJournalWrite) throw new java.io.IOException("write");
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

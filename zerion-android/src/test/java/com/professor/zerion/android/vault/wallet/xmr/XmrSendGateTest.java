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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The send gate performs fresh authentication after the signed snapshot exists,
 * issues a memory-only single-use token bound to the fingerprint, ownership,
 * epoch and lock generation, wipes secrets immediately, and fails closed on
 * every stale-authorization race. The final pre-relay check re-reads the native
 * object and rejects any mutation. Nothing is relayed.
 */
public class XmrSendGateTest {

	private static final String WALLET = "w1";
	private static final String DEST =
			"42ey1afDFnn4886T7196doS9GPMzexD9gXpsZJDwVjeRVdFCSoHnv7KPbBeGpzJBzHRCAs9UxqeoyFQMYbqSWYTfJJQAWDm";
	private static final String T1 =
			"1111111111111111111111111111111111111111111111111111111111111111";
	private static final String T2 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String T3 =
			"3333333333333333333333333333333333333333333333333333333333333333";

	private FakeStore store;
	private FakeGuard guard;
	private Clock clock;
	private XmrSendGate gate;
	private FakeMoneroEngine engine;
	private MoneroEngine.Session session;
	private Object flow;

	@Before
	public void setUp() {
		store = new FakeStore();
		store.password = "pw".toCharArray();
		guard = new FakeGuard(5, 5, true, WALLET);
		clock = new Clock();
		gate = new XmrSendGate(store, guard, clock, 60_000);
		engine = new FakeMoneroEngine();
		session = engine.create("w", "pw".toCharArray(), "English");
		flow = new Object();
	}

	private static byte[] fp32() {
		byte[] b = new byte[32];
		for (int i = 0; i < 32; i++) b[i] = (byte) (i + 3);
		return b;
	}

	private static FakeMoneroEngine.FakePrepared prepared(String... ids) {
		FakeMoneroEngine.FakePrepared p = new FakeMoneroEngine.FakePrepared();
		p.ids.addAll(Arrays.asList(ids));
		p.count = ids.length;
		p.amount = 1_000_000_000_000L;
		p.fee = 30_000_000L;
		p.dust = 5_000L;
		return p;
	}

	private XmrSendSnapshot snapshot(MoneroEngine.Prepared p)
			throws XmrError.XmrException {
		return XmrSendSnapshot.fromPrepared(WALLET, fp32(),
				XmrSendSnapshot.NETWORK_MAINNET, DEST,
				MoneroEngine.AddressKind.STANDARD, p);
	}

	private XmrSendOwnership ownership(Object prepared) {
		return new XmrSendOwnership(prepared, session, WALLET, 5, 5, flow);
	}

	@Test
	public void authorizeThenValidateSucceedsExactlyOnce() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1, T2);
		XmrSendSnapshot s = snapshot(p);
		XmrAuthToken token = gate.authorize(s, ownership(p), "pw".toCharArray());
		assertNotNull(token);
		assertTrue(token.isLive(clock.nowMonotonicMs()));
		gate.validateForRelay(token, s, p, session, flow);
		assertTrue(token.isConsumed());
	}

	@Test
	public void badPasswordFailsAndWipesCredential() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendSnapshot s = snapshot(p);
		char[] pw = "wrong".toCharArray();
		try {
			gate.authorize(s, ownership(p), pw);
			fail("wrong password must not authorize");
		} catch (XmrError.XmrException e) {
			assertEquals(XmrError.WRONG_PASSWORD, e.error);
		}
		assertAllZero("credential wiped after a failed check", pw);
		assertFalse("an unlocked vault alone does not authorize",
				gate.activeToken() != null);
	}

	@Test
	public void emptyPasswordIsRejected() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendSnapshot s = snapshot(p);
		try {
			gate.authorize(s, ownership(p), new char[0]);
			fail("empty password must be rejected");
		} catch (XmrError.XmrException e) {
			assertEquals(XmrError.EMPTY_PASSWORD, e.error);
		}
	}

	@Test
	public void secretsAreWipedOnSuccess() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendSnapshot s = snapshot(p);
		char[] pw = "pw".toCharArray();
		gate.authorize(s, ownership(p), pw);
		assertAllZero("credential wiped after a successful check", pw);
		assertNotNull(store.lastReturned);
		assertAllZero("decrypted mnemonic wiped immediately",
				store.lastReturned);
	}

	@Test
	public void tokenReplayIsRejected() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1, T2);
		XmrSendSnapshot s = snapshot(p);
		XmrAuthToken token = gate.authorize(s, ownership(p), "pw".toCharArray());
		gate.validateForRelay(token, s, p, session, flow);
		expect(XmrError.AUTHORIZATION_INVALID,
				() -> gate.validateForRelay(token, s, p, session, flow));
	}

	@Test
	public void tokenExpiresWhileWaiting() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendSnapshot s = snapshot(p);
		XmrAuthToken token = gate.authorize(s, ownership(p), "pw".toCharArray());
		clock.now += 60_001;
		assertFalse(token.isLive(clock.nowMonotonicMs()));
		expect(XmrError.AUTHORIZATION_INVALID,
				() -> gate.validateForRelay(token, s, p, session, flow));
	}

	@Test
	public void cancelInvalidatesTheAuthorization() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendSnapshot s = snapshot(p);
		XmrAuthToken token = gate.authorize(s, ownership(p), "pw".toCharArray());
		gate.invalidateActive();
		assertTrue(token.isInvalidated());
		expect(XmrError.AUTHORIZATION_INVALID,
				() -> gate.validateForRelay(token, s, p, session, flow));
	}

	@Test
	public void authForS1AfterS2ConstructedIsRejected() throws Exception {
		FakeMoneroEngine.FakePrepared p1 = prepared(T1);
		XmrSendSnapshot s1 = snapshot(p1);
		XmrAuthToken t1 = gate.authorize(s1, ownership(p1), "pw".toCharArray());
		FakeMoneroEngine.FakePrepared p2 = prepared(T2, T3);
		XmrSendSnapshot s2 = snapshot(p2);
		gate.authorize(s2, ownership(p2), "pw".toCharArray());
		assertTrue("issuing a new authorization kills the previous one",
				t1.isInvalidated());
		expect(XmrError.AUTHORIZATION_INVALID,
				() -> gate.validateForRelay(t1, s1, p1, session, flow));
	}

	@Test
	public void lockAfterAuthenticationFailsClosed() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendSnapshot s = snapshot(p);
		XmrAuthToken token = gate.authorize(s, ownership(p), "pw".toCharArray());
		guard.valid = false;
		guard.lockGeneration = 6;
		expect(XmrError.SESSION_INVALIDATED,
				() -> gate.validateForRelay(token, s, p, session, flow));
	}

	@Test
	public void walletSwitchAfterAuthenticationFailsClosed() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendSnapshot s = snapshot(p);
		XmrAuthToken token = gate.authorize(s, ownership(p), "pw".toCharArray());
		guard.walletId = "w2";
		expect(XmrError.SESSION_INVALIDATED,
				() -> gate.validateForRelay(token, s, p, session, flow));
	}

	@Test
	public void authorizeFailsIfSessionAlreadyChanged() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendSnapshot s = snapshot(p);
		XmrSendOwnership o = ownership(p);
		guard.sessionEpoch = 6;
		guard.lockGeneration = 6;
		expect(XmrError.SESSION_INVALIDATED,
				() -> gate.authorize(s, o, "pw".toCharArray()));
	}

	@Test
	public void finalReadMismatchIsTransactionMutated() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1, T2);
		XmrSendSnapshot s = snapshot(p);
		XmrAuthToken token = gate.authorize(s, ownership(p), "pw".toCharArray());
		p.ids.set(1, T3);
		expect(XmrError.TRANSACTION_MUTATED,
				() -> gate.validateForRelay(token, s, p, session, flow));
		assertTrue("a mutated transaction burns the authorization",
				token.isInvalidated());
	}

	@Test
	public void amountMutationIsTransactionMutated() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		XmrSendSnapshot s = snapshot(p);
		XmrAuthToken token = gate.authorize(s, ownership(p), "pw".toCharArray());
		p.amount = p.amount + 1;
		expect(XmrError.TRANSACTION_MUTATED,
				() -> gate.validateForRelay(token, s, p, session, flow));
	}

	@Test
	public void sameFingerprintButDifferentOwnershipFails() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1, T2);
		XmrSendSnapshot s = snapshot(p);
		XmrAuthToken token = gate.authorize(s, ownership(p), "pw".toCharArray());
		Object otherPrepared = prepared(T1, T2);
		expect(XmrError.AUTHORIZATION_INVALID, () -> gate.validateForRelay(token,
				s, (MoneroEngine.Prepared) otherPrepared, session, flow));
	}

	@Test
	public void concurrentConsumeClaimsExactlyOnce() throws Exception {
		FakeMoneroEngine.FakePrepared p = prepared(T1, T2);
		XmrSendSnapshot s = snapshot(p);
		XmrAuthToken token = gate.authorize(s, ownership(p), "pw".toCharArray());
		int threads = 8;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger wins = new AtomicInteger();
		List<Thread> ts = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			Thread t = new Thread(() -> {
				ready.countDown();
				try {
					go.await();
				} catch (InterruptedException ignored) {
				}
				if (token.consume(clock.nowMonotonicMs(), s.fingerprint(), p,
						session, WALLET, 5, 5, flow)) {
					wins.incrementAndGet();
				}
			});
			ts.add(t);
			t.start();
		}
		ready.await();
		go.countDown();
		for (Thread t : ts) t.join();
		assertEquals("exactly one concurrent consumer may win", 1, wins.get());
	}

	private final class Authorized {
		final FakeMoneroEngine.FakePrepared p;
		final XmrSendSnapshot s;
		final XmrAuthToken token;

		Authorized(String... ids) throws XmrError.XmrException {
			this.p = prepared(ids);
			this.s = snapshot(p);
			this.token = gate.authorize(s, ownership(p), "pw".toCharArray());
			p.inspections = 0;
		}
	}

	@Test
	public void successReadsTheNativeObjectExactlyForValidation()
			throws Exception {
		Authorized a = new Authorized(T1, T2);
		gate.validateForRelay(a.token, a.s, a.p, session, flow);
		assertTrue("a successful validation does read the native object",
				a.p.inspections > 0);
		assertTrue(a.token.isConsumed());
	}

	@Test
	public void wrongSessionMakesZeroNativeInspection() throws Exception {
		Authorized a = new Authorized(T1, T2);
		MoneroEngine.Session other =
				engine.create("w2", "pw".toCharArray(), "English");
		expect(XmrError.AUTHORIZATION_INVALID,
				() -> gate.validateForRelay(a.token, a.s, a.p, other, flow));
		assertEquals("a wrong session must dereference nothing native", 0,
				a.p.inspections);
	}

	@Test
	public void staleLockGenerationMakesZeroNativeInspection() throws Exception {
		Authorized a = new Authorized(T1);
		guard.lockGeneration = 6;
		expect(XmrError.AUTHORIZATION_INVALID,
				() -> gate.validateForRelay(a.token, a.s, a.p, session, flow));
		assertEquals(0, a.p.inspections);
	}

	@Test
	public void wrongFlowMakesZeroNativeInspection() throws Exception {
		Authorized a = new Authorized(T1);
		expect(XmrError.AUTHORIZATION_INVALID, () -> gate.validateForRelay(
				a.token, a.s, a.p, session, new Object()));
		assertEquals(0, a.p.inspections);
	}

	@Test
	public void disposedPreparedMakesZeroNativeInspection() throws Exception {
		Authorized a = new Authorized(T1);
		a.p.close();
		expect(XmrError.AUTHORIZATION_INVALID,
				() -> gate.validateForRelay(a.token, a.s, a.p, session, flow));
		assertEquals("a disposed transaction is never inspected", 0,
				a.p.inspections);
	}

	@Test
	public void replacedHandleMakesZeroNativeInspection() throws Exception {
		Authorized a = new Authorized(T1, T2);
		FakeMoneroEngine.FakePrepared other = prepared(T1, T2);
		expect(XmrError.AUTHORIZATION_INVALID,
				() -> gate.validateForRelay(a.token, a.s, other, session, flow));
		assertEquals(0, a.p.inspections);
		assertEquals(0, other.inspections);
	}

	@Test
	public void walletSwitchBeforeValidationMakesZeroNativeInspection()
			throws Exception {
		Authorized a = new Authorized(T1);
		guard.walletId = "w2";
		expect(XmrError.SESSION_INVALIDATED,
				() -> gate.validateForRelay(a.token, a.s, a.p, session, flow));
		assertEquals(0, a.p.inspections);
	}

	@Test
	public void lockBeforeValidationMakesZeroNativeInspection() throws Exception {
		Authorized a = new Authorized(T1);
		guard.valid = false;
		guard.lockGeneration = 6;
		expect(XmrError.SESSION_INVALIDATED,
				() -> gate.validateForRelay(a.token, a.s, a.p, session, flow));
		assertEquals(0, a.p.inspections);
	}

	@Test
	public void ownershipChangeDuringInspectionFailsClosed() throws Exception {
		Authorized a = new Authorized(T1, T2);
		a.p.onInspect = () -> guard.valid = false;
		expect(XmrError.SESSION_INVALIDATED,
				() -> gate.validateForRelay(a.token, a.s, a.p, session, flow));
		assertTrue("the change happened after the native read began",
				a.p.inspections > 0);
		assertFalse("a change during inspection consumes nothing",
				a.token.isConsumed());
		assertTrue(a.token.isInvalidated());
	}

	private static void assertAllZero(String why, char[] a) {
		for (char c : a) {
			if (c != '\0') fail(why);
		}
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

		FakeGuard(long epoch, long lockGen, boolean valid,
				@Nullable String walletId) {
			this.sessionEpoch = epoch;
			this.lockGeneration = lockGen;
			this.valid = valid;
			this.walletId = walletId;
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

	private static final class FakeStore
			implements com.professor.zerion.android.vault.wallet.xmr.XmrStore {
		@Nullable
		char[] password;
		@Nullable
		char[] lastReturned;

		@Override
		public char[] loadMnemonicChars(String walletId,
				@Nullable char[] password) throws Exception {
			if (password == null || password.length == 0) {
				throw new SecurityException("password required");
			}
			if (this.password == null || !Arrays.equals(password, this.password)) {
				throw new javax.crypto.AEADBadTagException("bad password");
			}
			lastReturned = "abandon ability able about".toCharArray();
			return lastReturned;
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

		@Nullable
		@Override
		public String readSpendJournal(String walletId) {
			return null;
		}

		@Override
		public void writeSpendJournal(String walletId, String journal) {
		}

		@Override
		public void removeSpendJournal(String walletId) {
		}
	}
}

package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The immutable snapshot binds exactly the reviewed and signed values, computes
 * the total debit as amount + fee (never adding dust), and fails closed on every
 * inconsistent or out-of-range native value. Arrays are defensively copied so
 * the snapshot cannot be changed after construction.
 */
public class XmrSendSnapshotTest {

	private static final String WALLET = "w1";
	private static final String DEST =
			"42ey1afDFnn4886T7196doS9GPMzexD9gXpsZJDwVjeRVdFCSoHnv7KPbBeGpzJBzHRCAs9UxqeoyFQMYbqSWYTfJJQAWDm";
	private static final String T1 =
			"1111111111111111111111111111111111111111111111111111111111111111";
	private static final String T2 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static byte[] fp32() {
		byte[] b = new byte[32];
		for (int i = 0; i < 32; i++) b[i] = (byte) (i + 1);
		return b;
	}

	private static XmrSendSnapshot valid() throws XmrError.XmrException {
		return XmrSendSnapshot.create(WALLET, fp32(),
				XmrSendSnapshot.NETWORK_MAINNET, DEST,
				MoneroEngine.AddressKind.STANDARD, 1_000_000_000_000L,
				30_000_000L, 5_000L, 2, Arrays.asList(T1, T2));
	}

	@Test
	public void validSnapshotBindsReviewedValues() throws Exception {
		XmrSendSnapshot s = valid();
		assertEquals(WALLET, s.walletId());
		assertEquals(MoneroEngine.AddressKind.STANDARD, s.destinationKind());
		assertEquals(1_000_000_000_000L, s.amountAtomic());
		assertEquals(30_000_000L, s.feeAtomic());
		assertEquals(5_000L, s.dustAtomic());
		assertEquals(2, s.txCount());
		assertEquals(Arrays.asList(T1, T2), s.txids());
		assertEquals(32, s.fingerprint().length);
	}

	@Test
	public void totalDebitIsAmountPlusFeeAndDustIsNeverAdded() throws Exception {
		XmrSendSnapshot s = valid();
		assertEquals("total debit is amount + fee", 1_000_030_000_000L,
				s.totalDebitAtomic());
		assertFalse("dust is never added on top of the debit",
				s.totalDebitAtomic()
						== s.amountAtomic() + s.feeAtomic() + s.dustAtomic());
	}

	@Test
	public void invalidTxCountFailsClosed() {
		expectInvalid(() -> XmrSendSnapshot.create(WALLET, fp32(),
				XmrSendSnapshot.NETWORK_MAINNET, DEST,
				MoneroEngine.AddressKind.STANDARD, 1, 1, 0, 2,
				Arrays.asList(T1)));
		expectInvalid(() -> XmrSendSnapshot.create(WALLET, fp32(),
				XmrSendSnapshot.NETWORK_MAINNET, DEST,
				MoneroEngine.AddressKind.STANDARD, 1, 1, 0, 0,
				new ArrayList<>()));
		expectInvalid(() -> XmrSendSnapshot.create(WALLET, fp32(),
				XmrSendSnapshot.NETWORK_MAINNET, DEST,
				MoneroEngine.AddressKind.STANDARD, 1, 1, 0, -1,
				new ArrayList<>()));
	}

	@Test
	public void malformedTxidFailsClosed() {
		String upper =
				"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
		String short64 = T1.substring(1);
		expectInvalid(() -> snapWithIds(Arrays.asList(T1, upper)));
		expectInvalid(() -> snapWithIds(Arrays.asList(T1, short64)));
		expectInvalid(() -> snapWithIds(Arrays.asList(T1, "not-hex")));
	}

	@Test
	public void negativeNativeValuesFailClosed() {
		expectInvalid(() -> amountFeeDust(-1, 1, 0));
		expectInvalid(() -> amountFeeDust(1, -1, 0));
		expectInvalid(() -> amountFeeDust(1, 1, -1));
	}

	@Test
	public void zeroAmountPreparedTransactionIsRejected() {
		expectInvalid(() -> amountFeeDust(0, 30_000_000L, 5_000L));
	}

	@Test
	public void feeBelowDustFailsClosed() {
		expectInvalid(() -> amountFeeDust(1, 100, 101));
	}

	@Test
	public void totalOverflowFailsClosed() {
		expectInvalid(() -> amountFeeDust(Long.MAX_VALUE, 1, 0));
	}

	@Test
	public void wrongNetworkAndBadFieldsFailClosed() {
		expectInvalid(() -> XmrSendSnapshot.create(WALLET, fp32(), 1, DEST,
				MoneroEngine.AddressKind.STANDARD, 1, 1, 0, 1,
				Arrays.asList(T1)));
		expectInvalid(() -> XmrSendSnapshot.create("", fp32(),
				XmrSendSnapshot.NETWORK_MAINNET, DEST,
				MoneroEngine.AddressKind.STANDARD, 1, 1, 0, 1,
				Arrays.asList(T1)));
		expectInvalid(() -> XmrSendSnapshot.create(WALLET, new byte[31],
				XmrSendSnapshot.NETWORK_MAINNET, DEST,
				MoneroEngine.AddressKind.STANDARD, 1, 1, 0, 1,
				Arrays.asList(T1)));
		expectInvalid(() -> XmrSendSnapshot.create(WALLET, fp32(),
				XmrSendSnapshot.NETWORK_MAINNET, "",
				MoneroEngine.AddressKind.STANDARD, 1, 1, 0, 1,
				Arrays.asList(T1)));
		expectInvalid(() -> XmrSendSnapshot.create(WALLET, fp32(),
				XmrSendSnapshot.NETWORK_MAINNET, DEST,
				MoneroEngine.AddressKind.INVALID, 1, 1, 0, 1,
				Arrays.asList(T1)));
	}

	@Test
	public void arraysAreDefensivelyCopiedAndSnapshotIsImmutable()
			throws Exception {
		byte[] fp = fp32();
		List<String> ids = new ArrayList<>(Arrays.asList(T1, T2));
		XmrSendSnapshot s = XmrSendSnapshot.create(WALLET, fp,
				XmrSendSnapshot.NETWORK_MAINNET, DEST,
				MoneroEngine.AddressKind.STANDARD, 10, 5, 0, 2, ids);
		byte[] beforeFp = s.fingerprint();
		fp[0] ^= 0x7f;
		ids.set(0, T2);
		s.txids().clear();
		s.primaryWalletFingerprint()[0] ^= 0x7f;
		assertEquals("mutating caller inputs cannot change the wallet id",
				WALLET, s.walletId());
		assertEquals("txids stay as captured", Arrays.asList(T1, T2), s.txids());
		assertArrayEquals("fingerprint is stable and copied", beforeFp,
				s.fingerprint());
		assertNotSame(s.fingerprint(), s.fingerprint());
	}

	private static XmrSendSnapshot snapWithIds(List<String> ids)
			throws XmrError.XmrException {
		return XmrSendSnapshot.create(WALLET, fp32(),
				XmrSendSnapshot.NETWORK_MAINNET, DEST,
				MoneroEngine.AddressKind.STANDARD, 1, 1, 0, ids.size(), ids);
	}

	private static XmrSendSnapshot amountFeeDust(long a, long f, long d)
			throws XmrError.XmrException {
		return XmrSendSnapshot.create(WALLET, fp32(),
				XmrSendSnapshot.NETWORK_MAINNET, DEST,
				MoneroEngine.AddressKind.STANDARD, a, f, d, 1,
				Arrays.asList(T1));
	}

	private interface Build {
		XmrSendSnapshot run() throws XmrError.XmrException;
	}

	private static void expectInvalid(Build b) {
		try {
			b.run();
			fail("expected SEND_SNAPSHOT_INVALID");
		} catch (XmrError.XmrException e) {
			assertEquals(XmrError.SEND_SNAPSHOT_INVALID, e.error);
		}
	}
}

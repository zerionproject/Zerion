package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * The canonical fingerprint is a deterministic pure function of the reviewed
 * fields, pinned by a golden vector so a future change to the encoding cannot go
 * unnoticed, and every fund-critical field is proven to change the digest. No
 * process-local value enters it, so identical fields always agree.
 */
public class XmrSendFingerprintTest {

	private static final String WALLET = "wallet-golden";
	private static final int NET = XmrSendSnapshot.NETWORK_MAINNET;
	private static final String DEST =
			"42ey1afDFnn4886T7196doS9GPMzexD9gXpsZJDwVjeRVdFCSoHnv7KPbBeGpzJBzHRCAs9UxqeoyFQMYbqSWYTfJJQAWDm";
	private static final int KIND = 1;
	private static final long AMOUNT = 1_000_000_000_000L;
	private static final long FEE = 30_000_000L;
	private static final long DUST = 5_000L;
	private static final long TOTAL = AMOUNT + FEE;
	private static final String T1 =
			"1111111111111111111111111111111111111111111111111111111111111111";
	private static final String T2 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static byte[] fp32() {
		byte[] b = new byte[32];
		for (int i = 0; i < 32; i++) b[i] = (byte) i;
		return b;
	}

	private static byte[] base() {
		return XmrSendFingerprint.compute(WALLET, fp32(), NET, DEST, KIND,
				AMOUNT, FEE, DUST, TOTAL, 2, new String[]{T1, T2});
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(String.format("%02x", x & 0xff));
		return sb.toString();
	}

	@Test
	public void goldenVectorIsPinned() {
		assertEquals("canonical encoding v1 must not drift",
				"1cc9ce8c5d6aef55bf61209e512d7b44a8052f8739e0c4074b027b2f1ec1663b",
				hex(base()));
	}

	@Test
	public void identicalFieldsProduceIdenticalFingerprint() {
		assertArrayEquals(base(), base());
	}

	@Test
	public void everyFundCriticalFieldChangesTheFingerprint() {
		byte[] b = base();
		byte[] otherFp = fp32();
		otherFp[0] ^= 0x01;
		assertDiffers(b, XmrSendFingerprint.compute("wallet-other", fp32(), NET,
				DEST, KIND, AMOUNT, FEE, DUST, TOTAL, 2, new String[]{T1, T2}));
		assertDiffers(b, XmrSendFingerprint.compute(WALLET, otherFp, NET, DEST,
				KIND, AMOUNT, FEE, DUST, TOTAL, 2, new String[]{T1, T2}));
		assertDiffers(b, XmrSendFingerprint.compute(WALLET, fp32(), NET + 1, DEST,
				KIND, AMOUNT, FEE, DUST, TOTAL, 2, new String[]{T1, T2}));
		assertDiffers(b, XmrSendFingerprint.compute(WALLET, fp32(), NET,
				DEST.substring(0, 94) + "1", KIND, AMOUNT, FEE, DUST, TOTAL, 2,
				new String[]{T1, T2}));
		assertDiffers(b, XmrSendFingerprint.compute(WALLET, fp32(), NET, DEST, 2,
				AMOUNT, FEE, DUST, TOTAL, 2, new String[]{T1, T2}));
		assertDiffers(b, XmrSendFingerprint.compute(WALLET, fp32(), NET, DEST,
				KIND, AMOUNT + 1, FEE, DUST, TOTAL, 2, new String[]{T1, T2}));
		assertDiffers(b, XmrSendFingerprint.compute(WALLET, fp32(), NET, DEST,
				KIND, AMOUNT, FEE + 1, DUST, TOTAL, 2, new String[]{T1, T2}));
		assertDiffers(b, XmrSendFingerprint.compute(WALLET, fp32(), NET, DEST,
				KIND, AMOUNT, FEE, DUST + 1, TOTAL, 2, new String[]{T1, T2}));
		assertDiffers(b, XmrSendFingerprint.compute(WALLET, fp32(), NET, DEST,
				KIND, AMOUNT, FEE, DUST, TOTAL + 1, 2, new String[]{T1, T2}));
		assertDiffers(b, XmrSendFingerprint.compute(WALLET, fp32(), NET, DEST,
				KIND, AMOUNT, FEE, DUST, TOTAL, 1, new String[]{T1}));
	}

	@Test
	public void changingAnyTxidChangesTheFingerprint() {
		byte[] b = base();
		String t2b =
				"2222222222222222222222222222222222222222222222222222222222222223";
		assertDiffers(b, XmrSendFingerprint.compute(WALLET, fp32(), NET, DEST,
				KIND, AMOUNT, FEE, DUST, TOTAL, 2, new String[]{T1, t2b}));
	}

	@Test
	public void changingTxidOrderChangesTheFingerprint() {
		byte[] b = base();
		assertDiffers(b, XmrSendFingerprint.compute(WALLET, fp32(), NET, DEST,
				KIND, AMOUNT, FEE, DUST, TOTAL, 2, new String[]{T2, T1}));
	}

	@Test
	public void sameRecipientAndAmountButDifferentTxidsDiffer() {
		byte[] first = base();
		String u1 =
				"abababababababababababababababababababababababababababababababab";
		String u2 =
				"cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd";
		byte[] second = XmrSendFingerprint.compute(WALLET, fp32(), NET, DEST,
				KIND, AMOUNT, FEE, DUST, TOTAL, 2, new String[]{u1, u2});
		assertDiffers(first, second);
	}

	private static void assertDiffers(byte[] a, byte[] b) {
		assertFalse("a bound field change must change the fingerprint",
				java.util.Arrays.equals(a, b));
	}
}

package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class XmrOutgoingHistoryTest {

	private static final String T1 =
			"1111111111111111111111111111111111111111111111111111111111111111";
	private static final String T2 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String IN =
			"3333333333333333333333333333333333333333333333333333333333333333";

	private static XmrPendingSend send(String txid, boolean converged, long ts) {
		return new XmrPendingSend("w1", new String[]{txid}, 3_000_000_000_000L,
				10_000_000L, 3_010_000_000_000L, 3_010_000_000_000L, ts, false,
				converged);
	}

	private static XmrTxInfo canonical(String txid, int dir, long amount,
			long height, long ts, long conf) {
		return XmrTxInfo.parse(txid + "," + dir + "," + amount + ",0," + height
				+ "," + ts + "," + conf + ",0,0,0");
	}

	private static XmrTxInfo find(List<XmrTxInfo> list, String txid) {
		for (XmrTxInfo t : list) if (t.txid.equals(txid)) return t;
		return null;
	}

	@Test
	public void pendingSendShowsOutgoingRowWithNoCanonical() {
		List<XmrTxInfo> merged = XmrWalletManager.mergeOutgoingHistory("w1",
				Collections.singletonList(send(T1, false, 1_700_000_000L)),
				Collections.emptyList());
		assertEquals(1, merged.size());
		assertEquals(XmrTxInfo.Direction.OUT, merged.get(0).direction);
		assertTrue(merged.get(0).pending);
	}

	@Test
	public void outgoingRowIsPermanentAndSuppressesCanonicalChangeIn() {
		XmrPendingSend converged = send(T1, true, 1_700_000_000L);
		XmrTxInfo changeIn = canonical(T1, 0, 500_000_000_000L, 3_750_000L,
				1_700_000_500L, 6);
		XmrTxInfo realIncoming = canonical(IN, 0, 9_000_000_000_000L, 3_749_000L,
				1_699_000_000L, 20);
		List<XmrTxInfo> merged = XmrWalletManager.mergeOutgoingHistory("w1",
				Collections.singletonList(converged),
				Arrays.asList(changeIn, realIncoming));

		XmrTxInfo t1 = find(merged, T1);
		assertEquals("the send's txid is shown once, as outgoing, not the "
				+ "change coming back in", XmrTxInfo.Direction.OUT, t1.direction);
		assertEquals("locally authoritative amount", 3_000_000_000_000L,
				t1.amountAtomic);
		assertEquals("enriched from canonical", 6L, t1.confirmations);
		assertFalse("mined, no longer pending", t1.pending);

		int t1Rows = 0;
		for (XmrTxInfo t : merged) if (t.txid.equals(T1)) t1Rows++;
		assertEquals("exactly one row per txid", 1, t1Rows);

		XmrTxInfo incoming = find(merged, IN);
		assertEquals("an unrelated incoming passes through",
				XmrTxInfo.Direction.IN, incoming.direction);
		assertEquals(2, merged.size());
	}

	@Test
	public void multipleSendsAllPersistNewestFirst() {
		List<XmrPendingSend> records = new ArrayList<>();
		records.add(send(T1, true, 1_700_000_000_000L));
		records.add(send(T2, false, 1_700_000_900_000L));
		List<XmrTxInfo> merged = XmrWalletManager.mergeOutgoingHistory("w1",
				records, Collections.emptyList());
		assertEquals(2, merged.size());
		assertEquals("newest first", T2, merged.get(0).txid);
		assertEquals(T1, merged.get(1).txid);
	}

	@Test
	public void recordsForOtherWalletsAreIgnored() {
		XmrPendingSend other = new XmrPendingSend("other", new String[]{T1},
				1L, 0L, 1L, 1L, 1_700_000_000L, false, false);
		List<XmrTxInfo> canonicalOnly = Collections.singletonList(
				canonical(IN, 0, 1L, 1L, 1L, 1L));
		List<XmrTxInfo> merged = XmrWalletManager.mergeOutgoingHistory("w1",
				Collections.singletonList(other), canonicalOnly);
		assertEquals(canonicalOnly, merged);
	}

	@Test
	public void noRecordsReturnsCanonicalUnchanged() {
		List<XmrTxInfo> canonicalOnly = Collections.singletonList(
				canonical(IN, 0, 1L, 1L, 1L, 1L));
		assertEquals(canonicalOnly, XmrWalletManager.mergeOutgoingHistory("w1",
				Collections.emptyList(), canonicalOnly));
	}

	private static java.util.Set<String> spent(String... txids) {
		return new java.util.HashSet<>(Arrays.asList(txids));
	}

	@Test
	public void reconcileReleasesReservationOnceWhenSpendWalletReportsTheSend() {
		XmrPendingSend p = send(T1, false, 1_700_000_000L);
		assertTrue(p.reservationDebit() > 0);
		List<XmrPendingSend> out = XmrWalletManager.convergeReflected(
				Collections.singletonList(p), spent(T1));
		assertEquals(1, out.size());
		assertTrue(out.get(0).converged);
		assertEquals(0L, out.get(0).reservationDebit());
	}

	@Test
	public void reconcileKeepsReservationForAnUnobservedSend() {
		XmrPendingSend p = send(T1, false, 1_700_000_000L);
		List<XmrPendingSend> in = Collections.singletonList(p);
		List<XmrPendingSend> out = XmrWalletManager.convergeReflected(in,
				spent(T2));
		assertEquals(in, out);
		assertFalse(out.get(0).converged);
		assertTrue(out.get(0).reservationDebit() > 0);
	}

	@Test
	public void reconcileNeverConvergesAnExternalSpendWithNoRecord() {
		List<XmrPendingSend> out = XmrWalletManager.convergeReflected(
				Collections.emptyList(), spent(T1, T2));
		assertTrue(out.isEmpty());
	}

	@Test
	public void reconcileLeavesAnAlreadyConvergedSendUntouched() {
		XmrPendingSend p = send(T1, true, 1_700_000_000L);
		List<XmrPendingSend> in = Collections.singletonList(p);
		assertEquals(in, XmrWalletManager.convergeReflected(in, spent(T1)));
	}

	@Test
	public void reconcileConvergesAMultiTxSendOnlyWhenEveryTxidIsReported() {
		XmrPendingSend p = new XmrPendingSend("w1", new String[]{T1, T2},
				3_000_000_000_000L, 10_000_000L, 3_010_000_000_000L,
				3_010_000_000_000L, 1_700_000_000L, false, false);
		List<XmrPendingSend> in = Collections.singletonList(p);
		assertEquals(in, XmrWalletManager.convergeReflected(in, spent(T1)));
		List<XmrPendingSend> out = XmrWalletManager.convergeReflected(in,
				spent(T1, T2));
		assertTrue(out.get(0).converged);
	}
}

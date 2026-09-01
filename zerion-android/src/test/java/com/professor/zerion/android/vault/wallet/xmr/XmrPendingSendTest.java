package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class XmrPendingSendTest {

	private static final String T1 =
			"1111111111111111111111111111111111111111111111111111111111111111";
	private static final String T2 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static XmrPendingSend single() {
		return new XmrPendingSend("w1", new String[]{T1}, 3_000_000_000_000L,
				10_000_000L, 3_010_000_000_000L, 1_700_000_000_000L, false, false);
	}

	@Test
	public void roundTripsThroughJson() {
		XmrPendingSend p = single();
		XmrPendingSend back = XmrPendingSend.fromJson(p.toJson());
		assertEquals(p, back);
	}

	@Test
	public void reservationHeldUntilConvergedNotUntilHistory() {
		XmrPendingSend p = new XmrPendingSend("w1", new String[]{T1, T2},
				5_000_000_000_000L, 20_000_000L, 5_020_000_000_000L,
				1_700_000_000_000L, false, false);
		assertEquals(5_020_000_000_000L, p.reservationDebit());
		XmrPendingSend c = p.asConverged();
		assertEquals(0L, c.reservationDebit());
	}

	@Test
	public void reservationCoversConsumedInputNotNetDebit() {

		long amount = 5_000_000_000_000L;
		long fee = 20_000_000L;
		long debit = amount + fee;
		long change = 4_000_000_000_000L;
		long reservedInput = debit + change;
		XmrPendingSend p = new XmrPendingSend("w1", new String[]{T1}, amount, fee,
				debit, reservedInput, 1_700_000_000_000L, false, false);
		assertEquals(reservedInput, p.reservationDebit());
		XmrPendingSend back = XmrPendingSend.fromJson(p.toJson());
		assertEquals(p, back);
		assertEquals(reservedInput, back.reservationDebit());
		assertEquals(0L, p.asConverged().reservationDebit());
	}

	@Test
	public void legacyRecordWithoutInputSumReservesNetDebit() {

		String legacy = "{\"w\":\"w1\",\"t\":[\"" + T1 + "\"],\"amt\":100,"
				+ "\"fee\":10,\"deb\":110,\"at\":1}";
		XmrPendingSend p = XmrPendingSend.fromJson(legacy);
		assertEquals(110L, p.reservationDebit());
	}

	@Test
	public void inputSumNeverBelowNetDebit() {

		XmrPendingSend p = new XmrPendingSend("w1", new String[]{T1}, 100L, 10L,
				110L, 5L, 1_700_000_000_000L, false, false);
		assertEquals(110L, p.reservationDebit());
	}

	@Test
	public void historyRowIsPendingUntilCanonicalThenEnriched() {
		XmrPendingSend p = single();
		XmrTxInfo pending = p.historyRow(T1, null);
		assertEquals(XmrTxInfo.Direction.OUT, pending.direction);
		assertEquals(3_000_000_000_000L, pending.amountAtomic);
		assertEquals(10_000_000L, pending.feeAtomic);
		assertTrue(pending.pending);
		assertEquals(0L, pending.confirmations);

		XmrTxInfo canonical = XmrTxInfo.parse(T1 + ",1,999,999,3750000,"
				+ "1700000500,6,0,0,0");
		XmrTxInfo merged = p.historyRow(T1, canonical);
		assertEquals("direction and amount stay locally authoritative",
				XmrTxInfo.Direction.OUT, merged.direction);
		assertEquals(3_000_000_000_000L, merged.amountAtomic);
		assertEquals(10_000_000L, merged.feeAtomic);
		assertEquals("confirmations merged from canonical", 6L,
				merged.confirmations);
		assertEquals("height merged from canonical", 3750000L, merged.height);
		assertFalse("no longer pending once mined", merged.pending);
	}

	@Test
	public void pendingRowUsesExactAmountAndFee() {
		XmrPendingSend p = single();
		XmrTxInfo row = p.pendingRow(T1);
		assertEquals(XmrTxInfo.Direction.OUT, row.direction);
		assertEquals(3_000_000_000_000L, row.amountAtomic);
		assertEquals(10_000_000L, row.feeAtomic);
		assertEquals(0L, row.confirmations);
		assertTrue(row.pending);
		assertFalse(row.failed);
		assertEquals(T1, row.txid);
	}

	@Test
	public void rejectsMalformedRecords() {
		assertNull(XmrPendingSend.fromJson(null));
		assertNull(XmrPendingSend.fromJson(""));
		assertNull(XmrPendingSend.fromJson("{}"));
		assertNull(XmrPendingSend.fromJson("not json"));

		assertNull(XmrPendingSend.fromJson(
				"{\"w\":\"w1\",\"t\":[\"xyz\"],\"amt\":1,\"fee\":0,"
						+ "\"deb\":1,\"at\":1}"));

		assertNull(XmrPendingSend.fromJson(
				"{\"w\":\"w1\",\"t\":[\"" + T1 + "\"],\"amt\":100,\"fee\":10,"
						+ "\"deb\":50,\"at\":1}"));

		assertNull(XmrPendingSend.fromJson(
				"{\"t\":[\"" + T1 + "\"],\"amt\":1,\"fee\":0,\"deb\":1,"
						+ "\"at\":1}"));
	}

	@Test
	public void listOfOutstandingSendsRoundTrips() {
		XmrPendingSend a = single();
		XmrPendingSend b = new XmrPendingSend("w1", new String[]{T2},
				1_000_000_000_000L, 5_000_000L, 1_005_000_000_000L,
				1_700_000_001_000L, true, false);
		List<XmrPendingSend> list = Arrays.asList(a, b);
		List<XmrPendingSend> back =
				XmrPendingSend.listFromJson(XmrPendingSend.listToJson(list));
		assertEquals(2, back.size());
		assertEquals(a, back.get(0));
		assertEquals(b, back.get(1));
	}

	@Test
	public void listFromJsonAcceptsLegacySingleObject() {
		XmrPendingSend a = single();
		List<XmrPendingSend> back = XmrPendingSend.listFromJson(a.toJson());
		assertEquals(1, back.size());
		assertEquals(a, back.get(0));
	}

	@Test
	public void listFromJsonDropsMalformedEntriesOnly() {
		String json = "[" + single().toJson() + ",{\"w\":\"w1\"}]";
		List<XmrPendingSend> back = XmrPendingSend.listFromJson(json);
		assertEquals(1, back.size());
		assertTrue(XmrPendingSend.listFromJson("garbage").isEmpty());
		assertTrue(XmrPendingSend.listFromJson(null).isEmpty());
	}

	@Test
	public void uncertainFlagRoundTrips() {
		XmrPendingSend p = new XmrPendingSend("w1", new String[]{T1},
				1L, 0L, 1L, 1_700_000_000_000L, true, false);
		XmrPendingSend back = XmrPendingSend.fromJson(p.toJson());
		assertTrue(back.uncertain);
	}
}

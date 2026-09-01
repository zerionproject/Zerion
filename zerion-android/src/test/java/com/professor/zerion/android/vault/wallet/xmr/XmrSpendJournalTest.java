package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The spend journal round-trips and, crucially, fails closed: any malformed,
 * inconsistent, wrong-version, oversized or wrong-wallet record is
 * JOURNAL_CORRUPTED, never silently accepted or treated as absent.
 */
public class XmrSpendJournalTest {

	private static final String WID = "wallet-1";
	private static final String AF =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String T1 =
			"1111111111111111111111111111111111111111111111111111111111111111";
	private static final String T2 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String EP =
			"tor:2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion:18089";

	private static XmrSpendJournal valid() throws XmrError.XmrException {
		return XmrSpendJournal.create(XmrSpendJournal.State.UNCERTAIN, WID, AF,
				Arrays.asList(T1, T2), EP, 1724900000000L,
				Collections.emptyList());
	}

	@Test
	public void roundTripsThroughSerialization() throws Exception {
		XmrSpendJournal j = valid();
		XmrSpendJournal back = XmrSpendJournal.parse(WID, j.serialize());
		assertEquals(XmrSpendJournal.State.UNCERTAIN, back.state());
		assertEquals(WID, back.walletId());
		assertEquals(AF, back.primaryFingerprintHex());
		assertEquals(2, back.txCount());
		assertEquals(Arrays.asList(T1, T2), back.txids());
		assertEquals(EP, back.relayEndpointId());
		assertEquals(1724900000000L, back.createdAtMs());
		assertTrue(back.rejectedTxids().isEmpty());
	}

	@Test
	public void relayingStateAndRejectionsRoundTrip() throws Exception {
		XmrSpendJournal j = XmrSpendJournal.create(
				XmrSpendJournal.State.RELAYING, WID, AF, Arrays.asList(T1, T2),
				EP, 1L, Collections.singletonList(T2));
		XmrSpendJournal back = XmrSpendJournal.parse(WID, j.serialize());
		assertEquals(XmrSpendJournal.State.RELAYING, back.state());
		assertEquals(Collections.singletonList(T2), back.rejectedTxids());
	}

	@Test
	public void unknownVersionIsCorrupt() {
		expectCorrupt(() -> XmrSpendJournal.parse(WID,
				"ZSPENDQ2\nstate=UNCERTAIN\nwid=" + WID + "\naf=" + AF
						+ "\nn=1\ntx=" + T1 + "\nep=" + EP + "\nat=1\n"));
	}

	@Test
	public void garbageIsCorrupt() {
		expectCorrupt(() -> XmrSpendJournal.parse(WID, "not a journal"));
		expectCorrupt(() -> XmrSpendJournal.parse(WID, ""));
	}

	@Test
	public void txCountMismatchIsCorrupt() {
		expectCorrupt(() -> XmrSpendJournal.parse(WID,
				"ZSPENDQ1\nstate=UNCERTAIN\nwid=" + WID + "\naf=" + AF
						+ "\nn=2\ntx=" + T1 + "\nep=" + EP + "\nat=1\n"));
	}

	@Test
	public void malformedTxidIsCorrupt() {
		String upper =
				"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
		expectCorrupt(() -> XmrSpendJournal.create(
				XmrSpendJournal.State.UNCERTAIN, WID, AF, Arrays.asList(upper),
				EP, 1L, Collections.emptyList()));
		expectCorrupt(() -> XmrSpendJournal.parse(WID,
				"ZSPENDQ1\nstate=UNCERTAIN\nwid=" + WID + "\naf=" + AF
						+ "\nn=1\ntx=zz\nep=" + EP + "\nat=1\n"));
	}

	@Test
	public void duplicateTxidsAreCorrupt() {
		expectCorrupt(() -> XmrSpendJournal.create(
				XmrSpendJournal.State.UNCERTAIN, WID, AF, Arrays.asList(T1, T1),
				EP, 1L, Collections.emptyList()));
	}

	@Test
	public void rejectionOfUnknownTxidIsCorrupt() {
		expectCorrupt(() -> XmrSpendJournal.create(
				XmrSpendJournal.State.UNCERTAIN, WID, AF, Arrays.asList(T1), EP,
				1L, Collections.singletonList(T2)));
	}

	@Test
	public void invalidEndpointIsCorrupt() {
		expectCorrupt(() -> XmrSpendJournal.create(
				XmrSpendJournal.State.UNCERTAIN, WID, AF, Arrays.asList(T1),
				"node.example.com:18081", 1L, Collections.emptyList()));
		expectCorrupt(() -> XmrSpendJournal.create(
				XmrSpendJournal.State.UNCERTAIN, WID, AF, Arrays.asList(T1), "",
				1L, Collections.emptyList()));
	}

	@Test
	public void badFingerprintOrTimestampIsCorrupt() {
		expectCorrupt(() -> XmrSpendJournal.create(
				XmrSpendJournal.State.UNCERTAIN, WID, "short", Arrays.asList(T1),
				EP, 1L, Collections.emptyList()));
		expectCorrupt(() -> XmrSpendJournal.create(
				XmrSpendJournal.State.UNCERTAIN, WID, AF, Arrays.asList(T1), EP,
				0L, Collections.emptyList()));
	}

	@Test
	public void wrongWalletIdIsCorrupt() throws Exception {
		String serialized = valid().serialize();
		expectCorrupt(() -> XmrSpendJournal.parse("other-wallet", serialized));
	}

	@Test
	public void oversizedInputIsCorrupt() {
		StringBuilder sb = new StringBuilder("ZSPENDQ1\n");
		for (int i = 0; i < 600; i++) sb.append("tx=").append(T1).append('\n');
		expectCorrupt(() -> XmrSpendJournal.parse(WID, sb.toString()));
	}

	@Test
	public void tooManyTransactionsIsCorrupt() {
		List<String> many = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			many.add(String.format("%064x", i));
		}
		expectCorrupt(() -> XmrSpendJournal.create(
				XmrSpendJournal.State.UNCERTAIN, WID, AF, many, EP, 1L,
				Collections.emptyList()));
	}

	private interface Run {
		void run() throws XmrError.XmrException;
	}

	private static void expectCorrupt(Run r) {
		try {
			r.run();
			fail("expected JOURNAL_CORRUPTED");
		} catch (XmrError.XmrException e) {
			assertEquals(XmrError.JOURNAL_CORRUPTED, e.error);
		}
	}
}

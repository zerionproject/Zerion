package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The reconciliation policy resolves a journal only on positive evidence (pool,
 * mined, or outgoing history) or a definitive rejection, and never on MISSED,
 * an error or a timeout, from any node, however many times seen.
 */
public class XmrSpendReconcilerTest {

	private static final String WID = "w1";
	private static final String AF =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String EP = "direct:203.0.113.5:18081";
	private static final String T1 =
			"1111111111111111111111111111111111111111111111111111111111111111";
	private static final String T2 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static XmrSpendJournal j(List<String> txids, List<String> rejected)
			throws XmrError.XmrException {
		return XmrSpendJournal.create(XmrSpendJournal.State.UNCERTAIN, WID, AF,
				txids, EP, 1L, rejected);
	}

	private static Set<String> set(String... s) {
		return new HashSet<>(Arrays.asList(s));
	}

	private static XmrTxLookup lookup(String txid, XmrTxLookup.Result r) {
		return new XmrTxLookup(txid, r, r == XmrTxLookup.Result.MINED ? 100 : -1);
	}

	@Test
	public void minedIsPositiveAndResolves() throws Exception {
		assertEquals(XmrSpendReconciler.Outcome.RESOLVED,
				XmrSpendReconciler.decide(j(Arrays.asList(T1),
						Collections.emptyList()), set(T1)));
	}

	@Test
	public void missedNeverResolves() throws Exception {
		XmrSpendJournal journal =
				j(Arrays.asList(T1), Collections.emptyList());
		assertEquals("a single MISSED stays quarantined",
				XmrSpendReconciler.Outcome.REMAIN_QUARANTINED,
				XmrSpendReconciler.decide(journal, set()));
	}

	@Test
	public void missedFromLookupsContributesNothing() {
		Set<String> accepted = XmrSpendReconciler.acceptedFrom(
				Arrays.asList(lookup(T1, XmrTxLookup.Result.MISSED),
						lookup(T2, XmrTxLookup.Result.LOOKUP_ERROR)),
				Collections.emptySet());
		assertTrue("MISSED and errors never count as accepted",
				accepted.isEmpty());
	}

	@Test
	public void poolMinedAndHistoryAllCountAsAccepted() {
		Set<String> accepted = XmrSpendReconciler.acceptedFrom(
				Arrays.asList(lookup(T1, XmrTxLookup.Result.IN_POOL)),
				set(T2));
		assertTrue(accepted.contains(T1));
		assertTrue("outgoing history is positive evidence",
				accepted.contains(T2));
	}

	@Test
	public void definitiveRejectionResolves() throws Exception {
		XmrSpendJournal journal =
				j(Arrays.asList(T1), Collections.singletonList(T1));
		assertEquals(XmrSpendReconciler.Outcome.RESOLVED,
				XmrSpendReconciler.decide(journal, set()));
	}

	@Test
	public void partialMultiTxStaysQuarantined() throws Exception {
		XmrSpendJournal journal =
				j(Arrays.asList(T1, T2), Collections.emptyList());
		assertEquals("one mined, one missing stays quarantined",
				XmrSpendReconciler.Outcome.REMAIN_QUARANTINED,
				XmrSpendReconciler.decide(journal, set(T1)));
	}

	@Test
	public void multiTxAllAcceptedResolves() throws Exception {
		XmrSpendJournal journal =
				j(Arrays.asList(T1, T2), Collections.emptyList());
		assertEquals(XmrSpendReconciler.Outcome.RESOLVED,
				XmrSpendReconciler.decide(journal, set(T1, T2)));
	}

	@Test
	public void multiTxAcceptedPlusRejectedResolves() throws Exception {
		XmrSpendJournal journal =
				j(Arrays.asList(T1, T2), Collections.singletonList(T2));
		assertEquals(XmrSpendReconciler.Outcome.RESOLVED,
				XmrSpendReconciler.decide(journal, set(T1)));
	}

	@Test
	public void repeatedMissedFromDifferentNodesStillNeverResolves()
			throws Exception {
		XmrSpendJournal journal =
				j(Arrays.asList(T1), Collections.emptyList());
		for (int round = 0; round < 5; round++) {
			Set<String> accepted = XmrSpendReconciler.acceptedFrom(
					Arrays.asList(lookup(T1, XmrTxLookup.Result.MISSED)),
					Collections.emptySet());
			assertEquals(XmrSpendReconciler.Outcome.REMAIN_QUARANTINED,
					XmrSpendReconciler.decide(journal, accepted));
		}
	}

	@Test
	public void timeoutStaysQuarantined() throws Exception {
		XmrSpendJournal journal =
				j(Arrays.asList(T1), Collections.emptyList());
		Set<String> accepted = XmrSpendReconciler.acceptedFrom(
				Arrays.asList(lookup(T1, XmrTxLookup.Result.LOOKUP_ERROR)),
				Collections.emptySet());
		assertEquals(XmrSpendReconciler.Outcome.REMAIN_QUARANTINED,
				XmrSpendReconciler.decide(journal, accepted));
		assertFalse(accepted.contains(T1));
	}
}

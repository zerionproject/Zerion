package com.professor.zerion.android.vault.wallet.btc.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class PrivacyAnalyzerTest {

	private static PrivacyAnalyzer.InputCoin coin(String op, String addr,
			UtxoOrigin origin, String cluster, long val, boolean reused) {
		return new PrivacyAnalyzer.InputCoin(op, addr, origin, cluster, val,
				reused);
	}

	private static PrivacyAnalyzer.AnalysisInput input(
			List<PrivacyAnalyzer.InputCoin> coins, boolean change,
			String changeCluster, boolean manual, long target) {
		return new PrivacyAnalyzer.AnalysisInput(coins, change, changeCluster,
				manual, target);
	}

	private static boolean has(PrivacyAnalyzer.Analysis a, String code) {
		for (PrivacyAnalyzer.Finding f : a.findings) {
			if (f.code.equals(code)) {
				return true;
			}
		}
		return false;
	}

	private static PrivacyAnalyzer.Finding find(PrivacyAnalyzer.Analysis a,
			String code) {
		for (PrivacyAnalyzer.Finding f : a.findings) {
			if (f.code.equals(code)) {
				return f;
			}
		}
		return null;
	}

	@Test
	public void oneClusterSpendIsHighNoMergeWarning() {
		PrivacyAnalyzer.Analysis a = PrivacyAnalyzer.analyze(input(
				Arrays.asList(coin("a:0", "addrA", UtxoOrigin.RECEIVE,
						"addr:addrA", 100000, false)), true, "addr:addrA",
				false, 40000));
		assertEquals(PrivacyAnalyzer.Level.HIGH, a.level);
		assertFalse(has(a, PrivacyAnalyzer.MERGE_CLUSTERS));
		assertTrue(has(a, PrivacyAnalyzer.SINGLE_CLUSTER));
		assertTrue(has(a, PrivacyAnalyzer.NO_REUSE));
		assertTrue(has(a, PrivacyAnalyzer.CHANGE_ISOLATED));
	}

	@Test
	public void multipleClustersDetectedAsLow() {
		PrivacyAnalyzer.Analysis a = PrivacyAnalyzer.analyze(input(Arrays.asList(
				coin("a:0", "addrA", UtxoOrigin.RECEIVE, "addr:addrA", 30000,
						false),
				coin("b:0", "addrB", UtxoOrigin.RECEIVE, "addr:addrB", 30000,
						false),
				coin("c:0", "addrC", UtxoOrigin.RECEIVE, "addr:addrC", 30000,
						false)), true, "addr:addrA", false, 50000));
		assertEquals(PrivacyAnalyzer.Level.LOW, a.level);
		assertEquals(3, find(a, PrivacyAnalyzer.MERGE_CLUSTERS).count);
	}

	@Test
	public void silentPaymentPlusNormalDetected() {
		PrivacyAnalyzer.Analysis a = PrivacyAnalyzer.analyze(input(Arrays.asList(
				coin("s:0", "sp", UtxoOrigin.SILENT_PAYMENT, "sp:s:0", 20000,
						false),
				coin("n:0", "addrA", UtxoOrigin.RECEIVE, "addr:addrA", 20000,
						false)), false, null, false, 30000));
		assertEquals(PrivacyAnalyzer.Level.LOW, a.level);
		assertTrue(has(a, PrivacyAnalyzer.SP_MIX));
	}

	@Test
	public void ordinaryAddressReuseDetected() {
		PrivacyAnalyzer.Analysis a = PrivacyAnalyzer.analyze(input(
				Arrays.asList(coin("a:0", "addrA", UtxoOrigin.RECEIVE,
						"addr:addrA", 100000, true)), false, null, false,
				40000));
		assertEquals(PrivacyAnalyzer.Level.MEDIUM, a.level);
		PrivacyAnalyzer.Finding r = find(a, PrivacyAnalyzer.ADDRESS_REUSE);
		assertEquals(PrivacyAnalyzer.Basis.FACT, r.basis);
	}

	@Test
	public void silentPaymentReusableIdentifierNotFlaggedAsReuse() {
		PrivacyAnalyzer.Analysis a = PrivacyAnalyzer.analyze(input(
				Arrays.asList(coin("s:0", "sp1xyz", UtxoOrigin.SILENT_PAYMENT,
						"sp:s:0", 100000, true)), false, null, false, 40000));
		assertFalse(has(a, PrivacyAnalyzer.ADDRESS_REUSE));
		assertEquals(PrivacyAnalyzer.Level.HIGH, a.level);
	}

	@Test
	public void commonInputLinkageIsAFact() {
		PrivacyAnalyzer.Analysis a = PrivacyAnalyzer.analyze(input(Arrays.asList(
				coin("a:0", "addrA", UtxoOrigin.RECEIVE, "addr:addrA", 30000,
						false),
				coin("a:1", "addrA", UtxoOrigin.RECEIVE, "addr:addrA", 30000,
						false)), false, null, false, 40000));
		PrivacyAnalyzer.Finding ci = find(a, PrivacyAnalyzer.COMMON_INPUT);
		assertEquals(PrivacyAnalyzer.Basis.FACT, ci.basis);
		assertEquals(2, ci.count);
	}

	@Test
	public void mergeFindingIsDerivedNotFact() {
		PrivacyAnalyzer.Analysis a = PrivacyAnalyzer.analyze(input(Arrays.asList(
				coin("a:0", "addrA", UtxoOrigin.RECEIVE, "addr:addrA", 30000,
						false),
				coin("b:0", "addrB", UtxoOrigin.RECEIVE, "addr:addrB", 30000,
						false)), false, null, false, 40000));
		assertEquals(PrivacyAnalyzer.Basis.DERIVED,
				find(a, PrivacyAnalyzer.MERGE_CLUSTERS).basis);
	}

	@Test
	public void unnecessaryExtraInputsDetected() {
		PrivacyAnalyzer.Analysis a = PrivacyAnalyzer.analyze(input(Arrays.asList(
				coin("a:0", "addrA", UtxoOrigin.RECEIVE, "addr:addrA", 100000,
						false),
				coin("a:1", "addrA", UtxoOrigin.RECEIVE, "addr:addrA", 100000,
						false)), true, "addr:addrA", true, 40000));
		assertTrue(has(a, PrivacyAnalyzer.EXTRA_INPUTS));
		assertEquals(PrivacyAnalyzer.Level.MEDIUM, a.level);
	}

	@Test
	public void changeRelationshipRecognized() {
		PrivacyAnalyzer.Analysis a = PrivacyAnalyzer.analyze(input(
				Arrays.asList(coin("a:0", "addrA", UtxoOrigin.RECEIVE,
						"addr:addrA", 100000, false)), true, "addr:addrA", false,
				40000));
		assertTrue(has(a, PrivacyAnalyzer.CHANGE_ISOLATED));
	}

	@Test
	public void futureChangeMergeDetected() {
		assertTrue(PrivacyAnalyzer.wouldFutureSpendMerge("addr:A",
				new HashSet<>(Arrays.asList("addr:B"))));
		assertFalse(PrivacyAnalyzer.wouldFutureSpendMerge("addr:A",
				new HashSet<>(Arrays.asList("addr:A"))));
	}

	@Test
	public void analyzerFailureNeverReportsHigh() {
		PrivacyAnalyzer.Analysis a = PrivacyAnalyzer.analyze(
				new PrivacyAnalyzer.AnalysisInput(null, false, null, false, 0));
		assertEquals(PrivacyAnalyzer.Level.UNAVAILABLE, a.level);
		assertNotEquals(PrivacyAnalyzer.Level.HIGH, a.level);
	}

	@Test
	public void emptySelectionIsNotHighWarning() {
		PrivacyAnalyzer.Analysis a = PrivacyAnalyzer.analyze(input(
				Collections.emptyList(), false, null, false, 0));
		assertEquals(PrivacyAnalyzer.Level.HIGH, a.level);
	}
}

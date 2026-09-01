package com.professor.zerion.android.vault.wallet.btc.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

public class PrivacyEngineTest {

	static final class MapStore implements PrivacyStore {
		final Set<String> frozen = new HashSet<>();
		final Map<String, String> labels = new HashMap<>();
		final Map<String, String> hints = new HashMap<>();

		public Set<String> frozen() { return frozen; }
		public Map<String, String> labels() { return labels; }
		public Map<String, String> originHints() { return hints; }
		public void setFrozen(String o, boolean f) {
			if (f) frozen.add(o); else frozen.remove(o);
		}
		public void setLabel(String o, @Nullable String l) {
			if (l == null) labels.remove(o); else labels.put(o, l);
		}
		public void putOriginHint(String a, String c) { hints.put(a, c); }
	}

	private static PrivacyEngine.UtxoView v(String op, long val, String addr,
			UtxoOrigin origin) {
		return new PrivacyEngine.UtxoView(op, val, addr, origin);
	}

	@Test
	public void sameAddressSharesClusterDifferentDoesNot() {
		List<PrivacyEngine.UtxoView> us = Arrays.asList(
				v("t1:0", 100, "addrA", UtxoOrigin.RECEIVE),
				v("t2:0", 200, "addrA", UtxoOrigin.RECEIVE),
				v("t3:0", 300, "addrB", UtxoOrigin.RECEIVE));
		List<PrivacyMeta> m = PrivacyEngine.classify(us, new MapStore());
		assertEquals(m.get(0).clusterId, m.get(1).clusterId);
		assertNotEquals(m.get(0).clusterId, m.get(2).clusterId);
	}

	@Test
	public void silentPaymentUtxosAreIsolated() {
		List<PrivacyEngine.UtxoView> us = Arrays.asList(
				v("s1:0", 100, "addrA", UtxoOrigin.SILENT_PAYMENT),
				v("s2:0", 100, "addrA", UtxoOrigin.SILENT_PAYMENT));
		List<PrivacyMeta> m = PrivacyEngine.classify(us, new MapStore());
		assertNotEquals(m.get(0).clusterId, m.get(1).clusterId);
		assertTrue(m.get(0).clusterId.startsWith("sp:"));
	}

	@Test
	public void originHintUnionsChangeIntoSpendingCluster() {
		MapStore s = new MapStore();
		s.putOriginHint("changeAddr", "addr:addrA");
		List<PrivacyEngine.UtxoView> us = Arrays.asList(
				v("t1:0", 100, "addrA", UtxoOrigin.RECEIVE),
				v("c1:0", 50, "changeAddr", UtxoOrigin.CHANGE));
		List<PrivacyMeta> m = PrivacyEngine.classify(us, s);
		assertEquals(m.get(0).clusterId, m.get(1).clusterId);
	}

	@Test
	public void frozenAndLabelOverlayApplied() {
		MapStore s = new MapStore();
		s.setFrozen("t1:0", true);
		s.setLabel("t1:0", "savings");
		List<PrivacyMeta> m = PrivacyEngine.classify(
				Arrays.asList(v("t1:0", 100, "addrA", UtxoOrigin.RECEIVE)), s);
		assertTrue(m.get(0).frozen);
		assertEquals("savings", m.get(0).label);
	}

	@Test
	public void standardOrderIsLargestFirst() {
		List<PrivacyMeta> m = PrivacyEngine.classify(Arrays.asList(
				v("t1:0", 100, "a", UtxoOrigin.RECEIVE),
				v("t2:0", 300, "b", UtxoOrigin.RECEIVE),
				v("t3:0", 200, "c", UtxoOrigin.RECEIVE)), new MapStore());
		List<PrivacyMeta> o = PrivacyEngine.orderForSelection(m,
				PrivacyEngine.Policy.STANDARD, 50);
		assertEquals(300, o.get(0).valueSat);
		assertEquals(200, o.get(1).valueSat);
		assertEquals(100, o.get(2).valueSat);
	}

	@Test
	public void frozenExcludedFromOrdering() {
		MapStore s = new MapStore();
		s.setFrozen("t2:0", true);
		List<PrivacyMeta> m = PrivacyEngine.classify(Arrays.asList(
				v("t1:0", 100, "a", UtxoOrigin.RECEIVE),
				v("t2:0", 999, "b", UtxoOrigin.RECEIVE)), s);
		List<PrivacyMeta> o = PrivacyEngine.orderForSelection(m,
				PrivacyEngine.Policy.STANDARD, 50);
		assertEquals(1, o.size());
		assertEquals("t1:0", o.get(0).outpoint);
	}

	@Test
	public void strictPrefersTightestSingleCluster() {
		List<PrivacyMeta> m = PrivacyEngine.classify(Arrays.asList(
				v("a1:0", 60000, "addrA", UtxoOrigin.RECEIVE),
				v("a2:0", 40000, "addrA", UtxoOrigin.RECEIVE),
				v("b1:0", 300000, "addrB", UtxoOrigin.RECEIVE)), new MapStore());
		List<PrivacyMeta> o = PrivacyEngine.orderForSelection(m,
				PrivacyEngine.Policy.STRICT, 50000);
		assertEquals("addr:addrA", o.get(0).clusterId);
		assertEquals("addr:addrA", o.get(1).clusterId);
		assertEquals("addr:addrB", o.get(2).clusterId);
	}

	@Test
	public void strictFallsBackToFewestClustersWhenNoneCovers() {
		List<PrivacyMeta> m = PrivacyEngine.classify(Arrays.asList(
				v("a1:0", 30000, "addrA", UtxoOrigin.RECEIVE),
				v("b1:0", 40000, "addrB", UtxoOrigin.RECEIVE)), new MapStore());
		List<PrivacyMeta> o = PrivacyEngine.orderForSelection(m,
				PrivacyEngine.Policy.STRICT, 50000);
		assertEquals("addr:addrB", o.get(0).clusterId);
		assertEquals("addr:addrA", o.get(1).clusterId);
	}

	@Test
	public void silentPaymentAndNormalUtxoWouldMerge() {
		List<PrivacyMeta> m = PrivacyEngine.classify(Arrays.asList(
				v("sp1:0", 5000, "addrA", UtxoOrigin.SILENT_PAYMENT),
				v("n1:0", 5000, "addrA", UtxoOrigin.RECEIVE)), new MapStore());
		assertNotEquals(m.get(0).clusterId, m.get(1).clusterId);
		assertTrue(PrivacyEngine.wouldMergeClusters(m));
	}

	@Test
	public void mergeDetection() {
		List<PrivacyMeta> m = PrivacyEngine.classify(Arrays.asList(
				v("a1:0", 100, "addrA", UtxoOrigin.RECEIVE),
				v("b1:0", 100, "addrB", UtxoOrigin.RECEIVE)), new MapStore());
		assertTrue(PrivacyEngine.wouldMergeClusters(m));
		assertFalse(PrivacyEngine.wouldMergeClusters(
				new ArrayList<>(m.subList(0, 1))));
	}
}

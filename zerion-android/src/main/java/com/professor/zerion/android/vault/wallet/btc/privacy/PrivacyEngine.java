package com.professor.zerion.android.vault.wallet.btc.privacy;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@NotNullByDefault
public final class PrivacyEngine {

	public enum Policy {
		STANDARD,
		STRICT
	}

	private PrivacyEngine() {
	}

	@NotNullByDefault
	public static final class UtxoView {
		public final String outpoint;
		public final long valueSat;
		public final String address;
		public final UtxoOrigin origin;

		public UtxoView(String outpoint, long valueSat, String address,
				UtxoOrigin origin) {
			this.outpoint = outpoint;
			this.valueSat = valueSat;
			this.address = address;
			this.origin = origin;
		}
	}

	public static String clusterOf(UtxoView u, Map<String, String> originHints) {
		if (u.origin == UtxoOrigin.SILENT_PAYMENT) {
			return "sp:" + u.outpoint;
		}
		String hint = originHints.get(u.address);
		return hint != null ? hint : "addr:" + u.address;
	}

	public static List<PrivacyMeta> classify(List<UtxoView> utxos,
			PrivacyStore store) {
		Set<String> frozen = store.frozen();
		Map<String, String> labels = store.labels();
		Map<String, String> hints = store.originHints();
		List<PrivacyMeta> out = new ArrayList<>();
		for (UtxoView u : utxos) {
			out.add(new PrivacyMeta(u.outpoint, u.valueSat, u.address, u.origin,
					clusterOf(u, hints), frozen.contains(u.outpoint),
					labels.get(u.outpoint)));
		}
		return out;
	}

	public static List<PrivacyMeta> orderForSelection(List<PrivacyMeta> metas,
			Policy policy, long target) {
		List<PrivacyMeta> avail = new ArrayList<>();
		for (PrivacyMeta m : metas) {
			if (!m.frozen) {
				avail.add(m);
			}
		}
		if (policy == Policy.STANDARD) {
			avail.sort(Comparator.comparingLong((PrivacyMeta m) -> m.valueSat)
					.reversed());
			return avail;
		}
		Map<String, List<PrivacyMeta>> byCluster = new LinkedHashMap<>();
		for (PrivacyMeta m : avail) {
			byCluster.computeIfAbsent(m.clusterId, k -> new ArrayList<>()).add(m);
		}
		Map<String, Long> totals = new LinkedHashMap<>();
		for (Map.Entry<String, List<PrivacyMeta>> e : byCluster.entrySet()) {
			long t = 0;
			for (PrivacyMeta m : e.getValue()) {
				t += m.valueSat;
			}
			totals.put(e.getKey(), t);
		}
		String chosen = null;
		long chosenTotal = Long.MAX_VALUE;
		for (Map.Entry<String, Long> e : totals.entrySet()) {
			if (e.getValue() >= target && e.getValue() < chosenTotal) {
				chosen = e.getKey();
				chosenTotal = e.getValue();
			}
		}
		List<String> order = new ArrayList<>();
		if (chosen != null) {
			order.add(chosen);
		}
		List<String> rest = new ArrayList<>(byCluster.keySet());
		rest.remove(chosen);
		rest.sort(Comparator.comparingLong(
				(String c) -> totals.getOrDefault(c, 0L)).reversed());
		order.addAll(rest);

		List<PrivacyMeta> out = new ArrayList<>();
		for (String c : order) {
			List<PrivacyMeta> group = byCluster.get(c);
			group.sort(Comparator.comparingLong((PrivacyMeta m) -> m.valueSat)
					.reversed());
			out.addAll(group);
		}
		return out;
	}

	public static Set<String> clustersIn(Collection<PrivacyMeta> selected) {
		Set<String> s = new HashSet<>();
		for (PrivacyMeta m : selected) {
			s.add(m.clusterId);
		}
		return s;
	}

	public static boolean wouldMergeClusters(Collection<PrivacyMeta> selected) {
		return clustersIn(selected).size() > 1;
	}
}

package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
public final class FeeGuard {

	static final double JUMP_FACTOR = 3.0;
	static final double DISAGREE_FACTOR = 3.0;
	static final double HOURLY_RISE = 1.5;
	static final long HOUR_MS = 3_600_000L;
	private static final double MAX_RISE_HOURS = 24.0;

	public interface Store {

		@Nullable
		String load();

		void save(String json);
	}

	interface Clock {
		long now();
	}

	static final Store NO_STORE = new Store() {
		@Nullable
		@Override
		public String load() {
			return null;
		}

		@Override
		public void save(String json) {
		}
	};

	static final class Verdict {

		final boolean jump;
		final boolean disagree;

		Verdict(boolean jump, boolean disagree) {
			this.jump = jump;
			this.disagree = disagree;
		}
	}

	private final Map<Integer, double[]> baseline = new HashMap<>();
	private final Map<Integer, Double> reviewBaseline = new HashMap<>();
	private final Map<Integer, Double> offered = new HashMap<>();
	private final Map<Integer, Boolean> disagree = new HashMap<>();
	private Store store = NO_STORE;
	private Clock clock = System::currentTimeMillis;
	private boolean loaded;

	synchronized void setStore(Store store) {
		this.store = store;
		baseline.clear();
		reviewBaseline.clear();
		loaded = false;
	}

	synchronized void setClock(Clock clock) {
		this.clock = clock;
	}

	synchronized double accept(int target, double primary, double second) {
		load();
		boolean hasPrimary = !Double.isNaN(primary);
		boolean hasSecond = !Double.isNaN(second);
		double rate;
		boolean disagreeNow = false;
		if (hasPrimary && hasSecond) {
			rate = Math.min(primary, second);
			disagreeNow = Math.max(primary, second) > DISAGREE_FACTOR * rate;
		} else if (hasPrimary) {
			rate = primary;
		} else if (hasSecond) {
			rate = second;
		} else {
			rate = BtcWallet.MIN_FEE_RATE;
		}
		boolean corroborated = hasPrimary && hasSecond && !disagreeNow;
		long now = clock.now();
		double[] prev = baseline.get(target);
		boolean changed = false;
		if (prev == null) {
			reviewBaseline.remove(target);
			baseline.put(target, new double[]{rate, now});
			changed = true;
		} else {
			reviewBaseline.put(target, prev[0]);
			double next;
			if (rate <= prev[0] || corroborated) {
				next = rate;
			} else {
				double hours = Math.min(MAX_RISE_HOURS,
						Math.max(0, now - (long) prev[1]) / (double) HOUR_MS);
				next = Math.min(rate, prev[0] * Math.pow(HOURLY_RISE, hours));
			}
			if (next != prev[0]) {
				baseline.put(target, new double[]{next, now});
				changed = true;
			}
		}
		disagree.put(target, disagreeNow);
		if (changed) save();
		return rate;
	}

	synchronized void offer(int target, double rate) {
		offered.put(target, rate);
	}

	synchronized Verdict review(double rate) {
		load();
		List<Integer> matches = new ArrayList<>();
		for (Map.Entry<Integer, Double> e : offered.entrySet()) {
			if (Math.abs(e.getValue() - rate) < 1e-9) matches.add(e.getKey());
		}
		boolean matched = !matches.isEmpty();
		if (!matched) matches.addAll(baselineTargets());
		double base = 0;
		boolean split = false;
		for (int t : matches) {
			Double b = reviewBaseline.get(t);
			if (b == null && !matched) {
				double[] cur = baseline.get(t);
				if (cur != null) b = cur[0];
			}
			if (b != null) base = Math.max(base, b);
			if (matched && Boolean.TRUE.equals(disagree.get(t))) split = true;
		}
		return new Verdict(base > 0 && rate > JUMP_FACTOR * base, split);
	}

	private List<Integer> baselineTargets() {
		List<Integer> out = new ArrayList<>(reviewBaseline.keySet());
		for (int t : baseline.keySet()) if (!out.contains(t)) out.add(t);
		return out;
	}

	private void load() {
		if (loaded) return;
		loaded = true;
		String json = store.load();
		if (json == null) return;
		try {
			JSONObject o = new JSONObject(json);
			Iterator<String> keys = o.keys();
			while (keys.hasNext()) {
				String k = keys.next();
				JSONObject e = o.optJSONObject(k);
				if (e == null) continue;
				int target = Integer.parseInt(k);
				double r = e.optDouble("r", Double.NaN);
				long at = e.optLong("at", 0L);
				if (r >= BtcWallet.MIN_FEE_RATE && r <= BtcWallet.MAX_FEE_RATE) {
					baseline.put(target, new double[]{r, at});
					reviewBaseline.put(target, r);
				}
			}
		} catch (Exception malformed) {
			baseline.clear();
			reviewBaseline.clear();
		}
	}

	private void save() {
		try {
			JSONObject o = new JSONObject();
			for (Map.Entry<Integer, double[]> e : baseline.entrySet()) {
				JSONObject v = new JSONObject();
				v.put("r", e.getValue()[0]);
				v.put("at", (long) e.getValue()[1]);
				o.put(String.valueOf(e.getKey()), v);
			}
			store.save(o.toString());
		} catch (Exception ignored) {
		}
	}
}

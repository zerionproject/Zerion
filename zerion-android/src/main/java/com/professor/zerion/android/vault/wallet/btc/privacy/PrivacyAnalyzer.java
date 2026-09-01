package com.professor.zerion.android.vault.wallet.btc.privacy;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
public final class PrivacyAnalyzer {

	private PrivacyAnalyzer() {
	}

	public enum Level {
		HIGH,
		MEDIUM,
		LOW,
		UNAVAILABLE
	}

	public enum Basis {
		FACT,
		DERIVED
	}

	public enum Severity {
		WARNING,
		CAUTION,
		INFO
	}

	public static final String MERGE_CLUSTERS = "merge_clusters";
	public static final String SP_MIX = "sp_mix";
	public static final String ADDRESS_REUSE = "address_reuse";
	public static final String EXTRA_INPUTS = "extra_inputs";
	public static final String COMMON_INPUT = "common_input";
	public static final String SINGLE_CLUSTER = "single_cluster";
	public static final String NO_REUSE = "no_reuse";
	public static final String NO_UNRELATED = "no_unrelated";
	public static final String CHANGE_ISOLATED = "change_isolated";

	@NotNullByDefault
	public static final class Finding {
		public final String code;
		public final Basis basis;
		public final Severity severity;
		public final int count;

		public Finding(String code, Basis basis, Severity severity, int count) {
			this.code = code;
			this.basis = basis;
			this.severity = severity;
			this.count = count;
		}
	}

	@NotNullByDefault
	public static final class Analysis {
		public final Level level;
		public final List<Finding> findings;

		public Analysis(Level level, List<Finding> findings) {
			this.level = level;
			this.findings = findings;
		}

		public static Analysis unavailable() {
			return new Analysis(Level.UNAVAILABLE, new ArrayList<>());
		}
	}

	@NotNullByDefault
	public static final class InputCoin {
		public final String outpoint;
		public final String address;
		public final UtxoOrigin origin;
		public final String clusterId;
		public final long valueSat;
		public final boolean reused;

		public InputCoin(String outpoint, String address, UtxoOrigin origin,
				String clusterId, long valueSat, boolean reused) {
			this.outpoint = outpoint;
			this.address = address;
			this.origin = origin;
			this.clusterId = clusterId;
			this.valueSat = valueSat;
			this.reused = reused;
		}
	}

	@NotNullByDefault
	public static final class AnalysisInput {
		public final List<InputCoin> inputs;
		public final boolean hasChange;
		@Nullable
		public final String changeCluster;
		public final boolean manualSelection;
		public final long targetSat;

		public AnalysisInput(List<InputCoin> inputs, boolean hasChange,
				@Nullable String changeCluster, boolean manualSelection,
				long targetSat) {
			this.inputs = inputs;
			this.hasChange = hasChange;
			this.changeCluster = changeCluster;
			this.manualSelection = manualSelection;
			this.targetSat = targetSat;
		}
	}

	public static Analysis analyze(AnalysisInput in) {
		try {
			List<Finding> f = new ArrayList<>();
			Set<String> clusters = new HashSet<>();
			boolean hasSp = false;
			boolean hasNonSp = false;
			int reused = 0;
			for (InputCoin c : in.inputs) {
				clusters.add(c.clusterId);
				if (c.origin == UtxoOrigin.SILENT_PAYMENT) {
					hasSp = true;
				} else {
					hasNonSp = true;
					if (c.reused) {
						reused++;
					}
				}
			}

			if (clusters.size() > 1) {
				f.add(new Finding(MERGE_CLUSTERS, Basis.DERIVED,
						Severity.WARNING, clusters.size()));
			} else {
				f.add(new Finding(SINGLE_CLUSTER, Basis.DERIVED, Severity.INFO,
						1));
				f.add(new Finding(NO_UNRELATED, Basis.DERIVED, Severity.INFO,
						0));
			}

			if (hasSp && hasNonSp) {
				f.add(new Finding(SP_MIX, Basis.DERIVED, Severity.WARNING, 0));
			}

			if (reused > 0) {
				f.add(new Finding(ADDRESS_REUSE, Basis.FACT, Severity.CAUTION,
						reused));
			} else {
				f.add(new Finding(NO_REUSE, Basis.FACT, Severity.INFO, 0));
			}

			if (in.inputs.size() > 1) {
				f.add(new Finding(COMMON_INPUT, Basis.FACT, Severity.INFO,
						in.inputs.size()));
			}

			if (isExtraInputs(in)) {
				f.add(new Finding(EXTRA_INPUTS, Basis.DERIVED, Severity.CAUTION,
						in.inputs.size()));
			}

			if (in.hasChange) {
				f.add(new Finding(CHANGE_ISOLATED, Basis.FACT, Severity.INFO,
						0));
			}

			Level level = Level.HIGH;
			for (Finding x : f) {
				if (x.severity == Severity.WARNING) {
					level = Level.LOW;
				}
			}
			if (level != Level.LOW) {
				for (Finding x : f) {
					if (x.severity == Severity.CAUTION) {
						level = Level.MEDIUM;
					}
				}
			}
			return new Analysis(level, f);
		} catch (Throwable t) {
			return Analysis.unavailable();
		}
	}

	private static boolean isExtraInputs(AnalysisInput in) {
		if (in.inputs.size() <= 1) {
			return false;
		}
		List<Long> values = new ArrayList<>();
		for (InputCoin c : in.inputs) {
			values.add(c.valueSat);
		}
		values.sort((a, b) -> Long.compare(b, a));
		long acc = 0;
		int minCount = 0;
		for (long v : values) {
			if (acc >= in.targetSat) {
				break;
			}
			acc += v;
			minCount++;
		}
		return in.inputs.size() > minCount;
	}

	public static boolean wouldFutureSpendMerge(String changeCluster,
			Set<String> otherClusters) {
		for (String c : otherClusters) {
			if (!c.equals(changeCluster)) {
				return true;
			}
		}
		return false;
	}
}

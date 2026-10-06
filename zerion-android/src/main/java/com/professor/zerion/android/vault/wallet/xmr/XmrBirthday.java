package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class XmrBirthday {

	private static final long BLOCK_MS = 120_000L;
	private static final long DAY_MS = 86_400_000L;
	static final long BASE_MARGIN_BLOCKS = 1440L;
	private static final long DRIFT_MARGIN_BLOCKS_PER_DAY = 24L;

	private static final long[][] CHECKPOINTS = {
			{1787875200000L, 3_749_900L},
	};

	private XmrBirthday() {
	}

	public static long latestCheckpointHeight() {
		return CHECKPOINTS[CHECKPOINTS.length - 1][1];
	}

	public static long heightForDate(long dateMillis) {
		long[] cp = nearest(dateMillis);
		long elapsedMs = dateMillis - cp[0];
		long blocks = Math.floorDiv(elapsedMs, BLOCK_MS);
		long distanceDays = Math.abs(elapsedMs) / DAY_MS;
		long margin = BASE_MARGIN_BLOCKS + distanceDays * DRIFT_MARGIN_BLOCKS_PER_DAY;
		long h = cp[1] + blocks - margin;
		return Math.max(h, 0);
	}

	public static long estimateHeight(long nowMillis) {
		long[] latest = CHECKPOINTS[CHECKPOINTS.length - 1];
		long floor = Math.max(latest[1] - BASE_MARGIN_BLOCKS, 0);
		return Math.max(heightForDate(nowMillis), floor);
	}

	private static long[] nearest(long dateMillis) {
		long[] best = CHECKPOINTS[0];
		long bestDist = Math.abs(dateMillis - best[0]);
		for (int i = 1; i < CHECKPOINTS.length; i++) {
			long d = Math.abs(dateMillis - CHECKPOINTS[i][0]);
			if (d <= bestDist) {
				best = CHECKPOINTS[i];
				bestDist = d;
			}
		}
		return best;
	}
}

package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Estimates a safe restore ("birthday") height for a Monero wallet from a
 * versioned table of trusted (timestamp, height) checkpoints, so scanning starts
 * conservatively before the requested date instead of from height 0. The
 * governing invariant is <b>false early is acceptable, false late is not</b>: the
 * estimate must never place the scan start after a transaction that occurred on
 * the requested date, because that silently skips funds; scanning a few extra
 * blocks only costs time.
 *
 * <p>The estimate extrapolates from the nearest checkpoint using Monero's target
 * block time, minus a safety margin that <i>grows with the extrapolation
 * distance</i>, so accumulated block-rate drift over long ranges can never move
 * the start later than the true height. A daemon may refine progress from this
 * point but the recovering-from-seed marker (see {@link XmrWalletManager}) keeps
 * it from moving the start later than this locally trusted bound.
 *
 * <p>Append a fresh, verified checkpoint to {@link #CHECKPOINTS} on each release
 * so the extrapolation distance, and thus the drift, stays small. Only add a
 * checkpoint whose height is verified against a trusted source; a checkpoint
 * whose height is too high for its date would violate the invariant.
 */
@NotNullByDefault
public final class XmrBirthday {

	private static final long BLOCK_MS = 120_000L;
	private static final long DAY_MS = 86_400_000L;
	private static final long BASE_MARGIN_BLOCKS = 1440L;
	private static final long DRIFT_MARGIN_BLOCKS_PER_DAY = 24L;

	/**
	 * Trusted checkpoints as {@code {unixMillis, height}}, ascending by time.
	 * Verified against the live chain. Append newer entries on release.
	 */
	private static final long[][] CHECKPOINTS = {
			{1787875200000L, 3_749_900L},
	};

	private XmrBirthday() {
	}

	/**
	 * The block height to restore/rescan from for a user-chosen calendar date,
	 * conservatively early (see the class invariant). Never below zero; a date
	 * older than the reach of the checkpoint table floors at genesis.
	 */
	public static long heightForDate(long dateMillis) {
		long[] cp = nearest(dateMillis);
		long elapsedMs = dateMillis - cp[0];
		long blocks = Math.floorDiv(elapsedMs, BLOCK_MS);
		long distanceDays = Math.abs(elapsedMs) / DAY_MS;
		long margin = BASE_MARGIN_BLOCKS + distanceDays * DRIFT_MARGIN_BLOCKS_PER_DAY;
		long h = cp[1] + blocks - margin;
		return Math.max(h, 0);
	}

	/**
	 * Restore height for a newly created wallet, whose history begins ~now.
	 * Uses the same table as {@link #heightForDate} but never scans from before
	 * the latest checkpoint (a created wallet has nothing older to find).
	 */
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

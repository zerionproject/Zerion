package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.function.LongSupplier;

import javax.annotation.concurrent.ThreadSafe;

/**
 * One failure counter for every place that checks the wallet credential:
 * the section gate and the per-transaction authorisation share it, so a
 * guessing run against the send dialog is slowed down exactly like one
 * against the gate. From the third failure on every further attempt has to
 * wait for a delay that doubles up to five minutes, and after three failures
 * the caller must drop any reviewed transaction and lock the wallet section
 * again. Time is read from the monotonic clock so a clock change cannot
 * shorten a delay.
 */
@ThreadSafe
@NotNullByDefault
public final class WalletCredentialThrottle {

	static final int FREE_FAILURES = 3;
	static final int FAILURES_BEFORE_RELOCK = 3;
	static final long BASE_DELAY_MS = 1_000;
	static final long MAX_DELAY_MS = 300_000;

	private final LongSupplier monotonicClock;
	private int failures = 0;
	private long delayUntil = 0;

	public WalletCredentialThrottle(LongSupplier monotonicClock) {
		this.monotonicClock = monotonicClock;
	}

	/** Restores the failure count persisted by an earlier process. */
	public synchronized void restoreFailures(int persistedFailures) {
		failures = Math.max(0, persistedFailures);
		if (failures >= FREE_FAILURES) {
			delayUntil = monotonicClock.getAsLong() + delayFor(failures);
		}
	}

	public synchronized int failures() {
		return failures;
	}

	/** True while a failed attempt still has to be waited out. */
	public synchronized boolean isThrottled() {
		return monotonicClock.getAsLong() < delayUntil;
	}

	public synchronized long remainingDelayMs() {
		return Math.max(0, delayUntil - monotonicClock.getAsLong());
	}

	public synchronized void recordSuccess() {
		failures = 0;
		delayUntil = 0;
	}

	/**
	 * Records a wrong credential and returns true if the caller must drop
	 * any pending transaction and lock the wallet section again.
	 */
	public synchronized boolean recordFailure() {
		failures++;
		if (failures >= FREE_FAILURES) {
			delayUntil = monotonicClock.getAsLong() + delayFor(failures);
		}
		return failures >= FAILURES_BEFORE_RELOCK;
	}

	static long delayFor(int failures) {
		int doublings = Math.max(0, failures - FREE_FAILURES);
		return Math.min(MAX_DELAY_MS, BASE_DELAY_MS << Math.min(doublings, 20));
	}
}

package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.function.LongSupplier;

import javax.annotation.concurrent.ThreadSafe;

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

	public synchronized void restoreFailures(int persistedFailures) {
		failures = Math.max(0, persistedFailures);
		if (failures >= FREE_FAILURES) {
			delayUntil = monotonicClock.getAsLong() + delayFor(failures);
		}
	}

	public synchronized int failures() {
		return failures;
	}

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

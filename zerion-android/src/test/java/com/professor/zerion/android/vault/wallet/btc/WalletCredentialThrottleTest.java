package com.professor.zerion.android.vault.wallet.btc;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static com.professor.zerion.android.vault.wallet.btc.WalletCredentialThrottle.BASE_DELAY_MS;
import static com.professor.zerion.android.vault.wallet.btc.WalletCredentialThrottle.FAILURES_BEFORE_RELOCK;
import static com.professor.zerion.android.vault.wallet.btc.WalletCredentialThrottle.MAX_DELAY_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shared throttle must make a guessing run against any credential
 * prompt slow, tell the caller when to drop the reviewed transaction and
 * re-lock, and carry its count across a restart.
 */
public class WalletCredentialThrottleTest {

	private final AtomicLong clock = new AtomicLong(1_000_000);
	private final WalletCredentialThrottle throttle =
			new WalletCredentialThrottle(clock::get);

	@Test
	public void delaysGrowFromTheThirdFailureAndAreCapped() {
		assertFalse(throttle.recordFailure());
		assertFalse(throttle.isThrottled());
		assertFalse(throttle.recordFailure());
		assertFalse(throttle.isThrottled());
		assertTrue("third failure re-locks", throttle.recordFailure());
		assertTrue(throttle.isThrottled());
		assertEquals(BASE_DELAY_MS, throttle.remainingDelayMs());
		clock.addAndGet(BASE_DELAY_MS);
		assertFalse(throttle.isThrottled());
		long previous = BASE_DELAY_MS;
		for (int i = 0; i < 12; i++) {
			throttle.recordFailure();
			long delay = throttle.remainingDelayMs();
			assertTrue(delay >= previous);
			assertTrue(delay <= MAX_DELAY_MS);
			previous = delay;
			clock.addAndGet(delay);
		}
		assertEquals(MAX_DELAY_MS, previous);
		assertEquals(2 * BASE_DELAY_MS, WalletCredentialThrottle.delayFor(4));
		assertEquals(4 * BASE_DELAY_MS, WalletCredentialThrottle.delayFor(5));
	}

	@Test
	public void aClockThatDoesNotAdvanceKeepsTheThrottle() {
		for (int i = 0; i < 3; i++) throttle.recordFailure();
		assertTrue(throttle.isThrottled());
		assertTrue(throttle.isThrottled());
		clock.addAndGet(BASE_DELAY_MS - 1);
		assertTrue(throttle.isThrottled());
		clock.addAndGet(1);
		assertFalse(throttle.isThrottled());
	}

	@Test
	public void successResetsEverything() {
		for (int i = 0; i < 5; i++) throttle.recordFailure();
		throttle.recordSuccess();
		assertEquals(0, throttle.failures());
		assertFalse(throttle.isThrottled());
		assertFalse(throttle.recordFailure());
	}

	@Test
	public void persistedFailuresCarryOverIntoANewProcess() {
		for (int i = 0; i < 4; i++) throttle.recordFailure();
		WalletCredentialThrottle restarted =
				new WalletCredentialThrottle(clock::get);
		restarted.restoreFailures(throttle.failures());
		assertEquals(4, restarted.failures());
		assertTrue("a restart does not clear the delay",
				restarted.isThrottled());
		assertTrue(restarted.recordFailure());
		assertEquals(5, restarted.failures());
		assertEquals(FAILURES_BEFORE_RELOCK, 3);
	}
}

package com.professor.zerion.android.login;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.login.BruteForceProtection.FailureResult;
import com.professor.zerion.android.login.BruteForceProtection.LockStatus;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicLong;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.login.BruteForceProtection.ATTEMPTS_BEFORE_FIRST_LOCKOUT;
import static com.professor.zerion.android.login.BruteForceProtection.FAILURE_DECAY_MS;
import static com.professor.zerion.android.login.BruteForceProtection.LOCKOUT_DURATION_MS;
import static com.professor.zerion.android.login.BruteForceProtection.MAX_LOCKOUT_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wrong passwords must only ever cost time unless the user has chosen the
 * erase policy, lockouts must survive a wall-clock jump, and the counter
 * must forget failures that are a day old.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BruteForceProtectionTest {

	private final AtomicLong wall = new AtomicLong(1_700_000_000_000L);
	private final AtomicLong mono = new AtomicLong(10_000);
	private SharedPreferences prefs;
	private BruteForceProtection protection;

	@Before
	public void setUp() {
		prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
				"bf-test-" + System.nanoTime(), Context.MODE_PRIVATE);
		protection = new BruteForceProtection(prefs, wall::get, mono::get);
	}

	private void advance(long ms) {
		wall.addAndGet(ms);
		mono.addAndGet(ms);
	}

	@Test
	public void byDefaultWrongPasswordsOnlyLockWithGrowingDurations() {
		assertFalse(protection.isWipeOnRepeatedFailures());
		assertEquals(FailureResult.Type.NORMAL_FAILURE,
				protection.recordFailedAttempt().type);
		assertEquals(FailureResult.Type.NORMAL_FAILURE,
				protection.recordFailedAttempt().type);
		long previous = 0;
		for (int attempt = ATTEMPTS_BEFORE_FIRST_LOCKOUT; attempt <= 20;
				attempt++) {
			FailureResult r = protection.recordFailedAttempt();
			assertEquals("attempt " + attempt + " must lock, never wipe",
					FailureResult.Type.LOCKOUT, r.type);
			assertTrue(r.lockoutDurationMs >= previous);
			assertTrue(r.lockoutDurationMs <= MAX_LOCKOUT_MS);
			previous = r.lockoutDurationMs;
			advance(r.lockoutDurationMs + 1);
		}
		assertEquals(MAX_LOCKOUT_MS, previous);
		assertEquals(LOCKOUT_DURATION_MS,
				BruteForceProtection.lockoutDurationFor(3));
		assertEquals(2 * LOCKOUT_DURATION_MS,
				BruteForceProtection.lockoutDurationFor(4));
		assertEquals(4 * LOCKOUT_DURATION_MS,
				BruteForceProtection.lockoutDurationFor(5));
	}

	@Test
	public void erasingIsAnOptInPolicy() {
		protection.setWipeOnRepeatedFailures(true);
		assertTrue(new BruteForceProtection(prefs, wall::get, mono::get)
				.isWipeOnRepeatedFailures());
		protection.recordFailedAttempt();
		protection.recordFailedAttempt();
		assertEquals(FailureResult.Type.LOCKOUT,
				protection.recordFailedAttempt().type);
		advance(LOCKOUT_DURATION_MS + 1);
		FailureResult fourth = protection.recordFailedAttempt();
		assertEquals(FailureResult.Type.FINAL_WARNING, fourth.type);
		assertEquals(2, fourth.attemptsRemaining);
		advance(MAX_LOCKOUT_MS);
		FailureResult fifth = protection.recordFailedAttempt();
		assertEquals(FailureResult.Type.FINAL_WARNING, fifth.type);
		assertEquals(1, fifth.attemptsRemaining);
		advance(MAX_LOCKOUT_MS);
		assertEquals(FailureResult.Type.WIPE_DATA,
				protection.recordFailedAttempt().type);
	}

	@Test
	public void movingTheWallClockForwardDoesNotShortenALockout() {
		protection.recordFailedAttempt();
		protection.recordFailedAttempt();
		FailureResult r = protection.recordFailedAttempt();
		assertEquals(FailureResult.Type.LOCKOUT, r.type);
		wall.addAndGet(r.lockoutDurationMs + 60_000);
		LockStatus status = protection.checkLockStatus();
		assertTrue("the monotonic clock still says locked", status.isLocked);
		mono.addAndGet(r.lockoutDurationMs + 1);
		assertFalse(protection.checkLockStatus().isLocked);
	}

	@Test
	public void movingTheWallClockBackwardsDoesNotShortenALockoutEither() {
		protection.recordFailedAttempt();
		protection.recordFailedAttempt();
		FailureResult r = protection.recordFailedAttempt();
		mono.addAndGet(r.lockoutDurationMs + 1);
		wall.addAndGet(-60_000);
		assertTrue("the wall clock still says locked",
				protection.checkLockStatus().isLocked);
		wall.addAndGet(r.lockoutDurationMs + 120_000);
		assertFalse(protection.checkLockStatus().isLocked);
	}

	@Test
	public void lockoutSurvivesARestartOfTheProtection() {
		protection.recordFailedAttempt();
		protection.recordFailedAttempt();
		protection.recordFailedAttempt();
		BruteForceProtection restarted =
				new BruteForceProtection(prefs, wall::get, mono::get);
		assertTrue(restarted.checkLockStatus().isLocked);
	}

	@Test
	public void failuresDecayAfterADay() {
		protection.recordFailedAttempt();
		protection.recordFailedAttempt();
		advance(FAILURE_DECAY_MS + 1);
		FailureResult r = protection.recordFailedAttempt();
		assertEquals("old failures no longer count",
				FailureResult.Type.NORMAL_FAILURE, r.type);
		assertEquals(ATTEMPTS_BEFORE_FIRST_LOCKOUT - 1, r.attemptsRemaining);
	}

	@Test
	public void successfulLoginResetsEverything() {
		protection.recordFailedAttempt();
		protection.recordFailedAttempt();
		protection.recordFailedAttempt();
		assertTrue(protection.checkLockStatus().isLocked);
		protection.recordSuccessfulLogin();
		assertFalse(protection.checkLockStatus().isLocked);
		assertEquals(FailureResult.Type.NORMAL_FAILURE,
				protection.recordFailedAttempt().type);
	}
}

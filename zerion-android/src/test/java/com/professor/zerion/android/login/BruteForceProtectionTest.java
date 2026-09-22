package com.professor.zerion.android.login;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.login.BruteForceProtection.FailureResult;
import com.professor.zerion.android.login.BruteForceProtection.LockStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.account.LoginThrottle;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;
import org.zerionproject.core.api.crypto.SecretKey;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.login.BruteForceProtection.ATTEMPTS_BEFORE_FIRST_LOCKOUT;
import static com.professor.zerion.android.login.BruteForceProtection.LOCKOUT_DURATION_MS;
import static com.professor.zerion.android.login.BruteForceProtection.MAX_LOCKOUT_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * AND-06: the login screen's policy reads one persisted, monotonic throttle
 * owned by the account manager. Wrong passwords only ever cost time unless
 * the user chose the erase policy, a lockout survives a restart, the wall
 * clock is never consulted, and failures a day old are forgotten.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BruteForceProtectionTest {

	private final AtomicLong mono = new AtomicLong(10_000);
	private File dir;
	private SharedPreferences prefs;
	private FakeAccountManager manager;
	private BruteForceProtection protection;

	@Before
	public void setUp() throws Exception {
		dir = Files.createTempDirectory("bf").toFile();
		prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
				"bf-test-" + System.nanoTime(), Context.MODE_PRIVATE);
		manager = new FakeAccountManager(dir, mono);
		protection = new BruteForceProtection(prefs, manager);
	}

	@After
	public void tearDown() {
		File[] kids = dir.listFiles();
		if (kids != null) for (File k : kids) k.delete();
		dir.delete();
	}

	private FailureResult wrongPassword() {
		try {
			manager.signIn("wrong".toCharArray());
		} catch (DecryptionException expected) {
		}
		return protection.recordFailedAttempt();
	}

	@Test
	public void byDefaultWrongPasswordsOnlyLockWithGrowingDurations() {
		assertFalse(protection.isWipeOnRepeatedFailures());
		assertEquals(FailureResult.Type.NORMAL_FAILURE, wrongPassword().type);
		assertEquals(FailureResult.Type.NORMAL_FAILURE, wrongPassword().type);
		long previous = 0;
		for (int attempt = ATTEMPTS_BEFORE_FIRST_LOCKOUT; attempt <= 20;
				attempt++) {
			FailureResult r = wrongPassword();
			assertEquals("attempt " + attempt + " must lock, never wipe",
					FailureResult.Type.LOCKOUT, r.type);
			assertTrue(r.lockoutDurationMs >= previous);
			assertTrue(r.lockoutDurationMs <= MAX_LOCKOUT_MS);
			previous = r.lockoutDurationMs;
			mono.addAndGet(r.lockoutDurationMs + 1);
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
		assertTrue(new BruteForceProtection(prefs, manager)
				.isWipeOnRepeatedFailures());
		wrongPassword();
		wrongPassword();
		assertEquals(FailureResult.Type.LOCKOUT, wrongPassword().type);
		mono.addAndGet(LOCKOUT_DURATION_MS + 1);
		FailureResult fourth = wrongPassword();
		assertEquals(FailureResult.Type.FINAL_WARNING, fourth.type);
		assertEquals(2, fourth.attemptsRemaining);
		mono.addAndGet(MAX_LOCKOUT_MS);
		FailureResult fifth = wrongPassword();
		assertEquals(FailureResult.Type.FINAL_WARNING, fifth.type);
		assertEquals(1, fifth.attemptsRemaining);
		mono.addAndGet(MAX_LOCKOUT_MS);
		assertEquals(FailureResult.Type.WIPE_DATA, wrongPassword().type);
	}

	@Test
	public void aLockedAccountRefusesWithoutCountingAndWithoutTheWallClock() {
		wrongPassword();
		wrongPassword();
		FailureResult r = wrongPassword();
		assertEquals(FailureResult.Type.LOCKOUT, r.type);
		LockStatus status = protection.checkLockStatus();
		assertTrue(status.isLocked);
		assertEquals(r.lockoutDurationMs, status.remainingMs);
		try {
			manager.signIn("right".toCharArray());
		} catch (DecryptionException expected) {
			assertEquals(DecryptionResult.INVALID_CIPHERTEXT,
					expected.getDecryptionResult());
		}
		assertEquals("a refused attempt is not counted", 3,
				manager.failedSignInAttempts());
		mono.addAndGet(r.lockoutDurationMs + 1);
		assertFalse(protection.checkLockStatus().isLocked);
	}

	@Test
	public void lockoutSurvivesARestart() throws Exception {
		wrongPassword();
		wrongPassword();
		wrongPassword();
		FakeAccountManager restarted = new FakeAccountManager(dir, mono);
		BruteForceProtection again = new BruteForceProtection(prefs, restarted);
		assertTrue(again.checkLockStatus().isLocked);
		assertEquals(3, restarted.failedSignInAttempts());
	}

	@Test
	public void failuresDecayAfterADay() {
		wrongPassword();
		wrongPassword();
		mono.addAndGet(86_400_000L + 1);
		FailureResult r = wrongPassword();
		assertEquals("old failures no longer count",
				FailureResult.Type.NORMAL_FAILURE, r.type);
		assertEquals(ATTEMPTS_BEFORE_FIRST_LOCKOUT - 1, r.attemptsRemaining);
	}

	@Test
	public void successfulLoginResetsEverything() throws Exception {
		wrongPassword();
		wrongPassword();
		mono.addAndGet(1);
		manager.signIn("right".toCharArray());
		protection.recordSuccessfulLogin();
		assertFalse(protection.checkLockStatus().isLocked);
		assertEquals(0, manager.failedSignInAttempts());
		assertEquals(FailureResult.Type.NORMAL_FAILURE, wrongPassword().type);
	}

	/** The manager's contract: count on refusal, refuse while locked, reset on success. */
	private static final class FakeAccountManager implements AccountManager {

		private final LoginThrottle throttle;

		FakeAccountManager(File dir, AtomicLong mono) {
			throttle = new LoginThrottle(
					LoginThrottle.fileStore(new File(dir, "login.lockout")),
					mono::get, () -> "boot", LoginThrottle.SIGN_IN);
		}

		@Override
		public boolean hasDatabaseKey() {
			return false;
		}

		@Nullable
		@Override
		public SecretKey getDatabaseKey() {
			return null;
		}

		@Override
		public boolean accountExists() {
			return true;
		}

		@Override
		public boolean createAccount(String name, char[] password) {
			return false;
		}

		@Override
		public void deleteAccount() {
			throttle.reset();
		}

		@Override
		public void signIn(char[] password) throws DecryptionException {
			if (throttle.remainingLockoutMs() > 0) {
				throw new DecryptionException(
						DecryptionResult.INVALID_CIPHERTEXT);
			}
			if (Arrays.equals(password, "right".toCharArray())) {
				throttle.reset();
				return;
			}
			throttle.recordFailure();
			throw new DecryptionException(DecryptionResult.INVALID_PASSWORD);
		}

		@Override
		public long signInLockoutRemainingMs() {
			return throttle.remainingLockoutMs();
		}

		@Override
		public int failedSignInAttempts() {
			return throttle.failures();
		}

		@Override
		public void changePassword(char[] oldPassword, char[] newPassword) {
		}
	}
}

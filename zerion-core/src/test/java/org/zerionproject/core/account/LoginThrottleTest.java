package org.zerionproject.core.account;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

/**
 * AND-06: a failed-attempt lockout runs on the monotonic clock, survives a
 * force-stop through its state file, is restarted rather than ended by a
 * reboot, and is never shortened by the wall clock, which it never reads.
 */
public class LoginThrottleTest {

	private final File testDir = getTestDirectory();
	private final File state = new File(testDir, "login.lockout");
	private final AtomicLong mono = new AtomicLong(50_000);
	private final AtomicReference<String> boot = new AtomicReference<>("boot-a");

	private LoginThrottle throttle(LoginThrottle.Policy policy) {
		return new LoginThrottle(LoginThrottle.fileStore(state), mono::get,
				boot::get, policy);
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	@Test
	public void signInPolicyLocksOnTheThirdFailureWithDoublingDurations() {
		LoginThrottle t = throttle(LoginThrottle.SIGN_IN);
		assertEquals(0, t.recordFailure());
		assertEquals(0, t.recordFailure());
		assertEquals(0, t.remainingLockoutMs());
		assertEquals(300_000L, t.recordFailure());
		assertEquals(300_000L, t.remainingLockoutMs());
		mono.addAndGet(300_001);
		assertEquals(0, t.remainingLockoutMs());
		assertEquals(600_000L, t.recordFailure());
		long previous = 600_000L;
		for (int i = 0; i < 20; i++) {
			mono.addAndGet(previous + 1);
			long next = t.recordFailure();
			assertTrue(next >= previous);
			assertTrue(next <= 86_400_000L);
			previous = next;
		}
		assertEquals(86_400_000L, previous);
	}

	@Test
	public void lockoutContinuesAcrossAProcessRestart() {
		LoginThrottle t = throttle(LoginThrottle.SIGN_IN);
		t.recordFailure();
		t.recordFailure();
		t.recordFailure();
		assertTrue(state.exists());
		mono.addAndGet(100_000);
		LoginThrottle restarted = throttle(LoginThrottle.SIGN_IN);
		assertEquals(3, restarted.failures());
		assertEquals(200_000L, restarted.remainingLockoutMs());
		mono.addAndGet(200_000);
		assertEquals(0, restarted.remainingLockoutMs());
	}

	@Test
	public void rebootRestartsTheLockoutInsteadOfEndingIt() {
		LoginThrottle t = throttle(LoginThrottle.SIGN_IN);
		t.recordFailure();
		t.recordFailure();
		t.recordFailure();
		boot.set("boot-b");
		mono.set(1_000);
		LoginThrottle restarted = throttle(LoginThrottle.SIGN_IN);
		assertEquals(3, restarted.failures());
		assertEquals(300_000L, restarted.remainingLockoutMs());
		LoginThrottle again = throttle(LoginThrottle.SIGN_IN);
		assertEquals("the re-anchored state is persisted", 300_000L,
				again.remainingLockoutMs());
	}

	@Test
	public void failuresDecayOnlyAfterAQuietDay() {
		LoginThrottle t = throttle(LoginThrottle.SIGN_IN);
		t.recordFailure();
		t.recordFailure();
		mono.addAndGet(86_400_000L);
		assertEquals(2, t.failures());
		mono.addAndGet(1);
		assertEquals(0, t.failures());
		assertFalse("a cleared throttle keeps no state file", state.exists()
				&& state.length() > 0);
		assertEquals(0, t.recordFailure());
	}

	@Test
	public void aLockoutInForceDoesNotDecay() {
		LoginThrottle t = throttle(LoginThrottle.SIGN_IN);
		for (int i = 0; i < 12; i++) {
			mono.addAndGet(t.recordFailure() + 1);
		}
		long lockout = t.recordFailure();
		assertEquals(86_400_000L, lockout);
		mono.addAndGet(86_400_000L - 1);
		assertEquals(13, t.failures());
		assertEquals(1, t.remainingLockoutMs());
	}

	@Test
	public void resetForgetsEverything() {
		LoginThrottle t = throttle(LoginThrottle.SIGN_IN);
		t.recordFailure();
		t.recordFailure();
		t.recordFailure();
		t.reset();
		assertEquals(0, t.failures());
		assertEquals(0, t.remainingLockoutMs());
		assertEquals(0, throttle(LoginThrottle.SIGN_IN).failures());
	}

	@Test
	public void vaultPolicyCostsTimeFromTheFirstFailureAndForgetsAfterAMinute() {
		LoginThrottle t = throttle(LoginThrottle.VAULT);
		assertEquals(1_000L, t.recordFailure());
		mono.addAndGet(1_001);
		assertEquals(3_000L, t.recordFailure());
		mono.addAndGet(3_001);
		assertEquals(5_000L, t.recordFailure());
		for (int i = 3; i < 12; i++) {
			mono.addAndGet(t.remainingLockoutMs() + 1);
			t.recordFailure();
		}
		assertEquals(19_000L, t.recordFailure());
		mono.addAndGet(19_000 + 60_001);
		assertEquals(0, t.failures());
	}

	@Test
	public void corruptStateIsDiscardedNotTrusted() throws Exception {
		testDir.mkdirs();
		LoginThrottle.writeDurably(state, "garbage".getBytes("UTF-8"));
		LoginThrottle t = throttle(LoginThrottle.SIGN_IN);
		assertEquals(0, t.failures());
		assertEquals(0, t.remainingLockoutMs());
		LoginThrottle.writeDurably(state, "1,3,boot-a,x,y".getBytes("UTF-8"));
		assertEquals(0, throttle(LoginThrottle.SIGN_IN).failures());
	}

	@Test
	public void durableWriteLeavesNoTemporaryFileAndReplacesAtomically()
			throws Exception {
		File target = new File(testDir, "db.key");
		LoginThrottle.writeDurably(target, "one".getBytes("UTF-8"));
		LoginThrottle.writeDurably(target, "two".getBytes("UTF-8"));
		assertEquals("two", new String(java.nio.file.Files.readAllBytes(
				target.toPath()), "UTF-8"));
		String[] names = testDir.list();
		assertTrue(names != null);
		for (String n : names) assertFalse(n.endsWith(".tmp"));
	}
}

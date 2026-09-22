package com.professor.zerion.android.decoy;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A2-AND-10: a failed decoy code locks the next attempt out at once. */
public class DecoyUnlockThrottleTest {

	@Test
	public void aFailureLocksAndSurvivesARestartUntilItPasses()
			throws Exception {
		File dir = Files.createTempDirectory("decoy").toFile();
		File state = new File(dir, "decoy.lockout");
		DecoyUnlockThrottle t = new DecoyUnlockThrottle(state);
		assertTrue(t.allow());
		t.failed();
		assertFalse("locked right after one failure", t.allow());
		DecoyUnlockThrottle restarted = new DecoyUnlockThrottle(state);
		assertFalse("the lockout is persisted", restarted.allow());
		restarted.passed();
		assertTrue(new DecoyUnlockThrottle(state).allow());
	}
}

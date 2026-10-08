package com.professor.zerion.android.update;

import org.junit.Test;

import static com.professor.zerion.android.update.UpdatePolicy.CHECK_INTERVAL_MS;
import static com.professor.zerion.android.update.UpdatePolicy.LATER_MS;
import static com.professor.zerion.android.update.UpdatePolicy.RETRY_INTERVAL_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdatePolicyTest {

	private static final long NOW = 1_800_000_000_000L;

	private static ReleaseAnnouncement version(long code) {
		return new ReleaseAnnouncement("3.0." + (code / 100 - 300), code,
				"u", "p", "s");
	}

	@Test
	public void anAutomaticCheckRunsOnlyWhenEverythingAllowsIt() {
		assertTrue(UpdatePolicy.dueForAutomaticCheck(true, false, true, NOW, 0));
		assertFalse("switched off",
				UpdatePolicy.dueForAutomaticCheck(false, false, true, NOW, 0));
		assertFalse("store install",
				UpdatePolicy.dueForAutomaticCheck(true, true, true, NOW, 0));
		assertFalse("Tor not connected",
				UpdatePolicy.dueForAutomaticCheck(true, false, false, NOW, 0));
		assertFalse("checked less than a day ago",
				UpdatePolicy.dueForAutomaticCheck(true, false, true, NOW,
						NOW + 1000));
		assertTrue("a clock moved far back does not block checks for days",
				UpdatePolicy.dueForAutomaticCheck(true, false, true, NOW,
						NOW + CHECK_INTERVAL_MS + 1));
	}

	@Test
	public void theNextCheckIsADayLaterOrAnHourAfterAFailure() {
		assertEquals(NOW + CHECK_INTERVAL_MS, UpdatePolicy.nextCheckAfter(
				UpdateChecker.Outcome.UP_TO_DATE, NOW));
		assertEquals(NOW + CHECK_INTERVAL_MS, UpdatePolicy.nextCheckAfter(
				UpdateChecker.Outcome.NOT_VERIFIED, NOW));
		assertEquals(NOW + RETRY_INTERVAL_MS, UpdatePolicy.nextCheckAfter(
				UpdateChecker.Outcome.UNREACHABLE, NOW));
	}

	@Test
	public void thePopUpAppearsOnlyForANewerVersionAndOnceADay() {
		assertFalse("same version", UpdatePolicy.shouldPopUp(version(31600),
				31600, 0, 0, NOW));
		assertFalse("older version", UpdatePolicy.shouldPopUp(version(31500),
				31600, 0, 0, NOW));
		assertTrue(UpdatePolicy.shouldPopUp(version(31700), 31600, 0, 0, NOW));
		assertFalse("already shown today", UpdatePolicy.shouldPopUp(
				version(31700), 31600, 31700, NOW - 1000, NOW));
		assertTrue("shown again the next day", UpdatePolicy.shouldPopUp(
				version(31700), 31600, 31700, NOW - LATER_MS, NOW));
		assertTrue("a newer version is not hidden by an older Later",
				UpdatePolicy.shouldPopUp(version(31800), 31600, 31700,
						NOW - 1000, NOW));
	}
}

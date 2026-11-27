package org.zerionproject.core.contact.request;

import org.junit.Test;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.CALIBRATION_BLOCK_HASHES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SENDER_DIFFICULTY_CAP;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SENDER_SOLVE_BUDGET_MS;

public class ContactRequestSolveBudgetTest extends BrambleTestCase {

	private final CryptoComponent crypto = PasswordCryptoForTests.create();

	@Test
	public void aSolveGivesUpWhenItsTimeBudgetIsSpent() {
		long[] now = {0};
		int[] reads = {0};
		ContactRequestCrypto c = new ContactRequestCrypto(crypto, () -> {
			reads[0]++;
			return now[0] += SENDER_SOLVE_BUDGET_MS / 4;
		});
		assertNull(c.solve(new byte[32], new byte[32], SENDER_DIFFICULTY_CAP,
				new Random(11), () -> false));
		assertTrue("it stops at the budget instead of spinning, read the"
				+ " clock " + reads[0] + " times", reads[0] <= 6);
	}

	@Test
	public void aLevelThisPhoneCannotSolveInTimeIsRefusedBeforeAnyWork() {
		long[] now = {0};
		ContactRequestCrypto c = new ContactRequestCrypto(crypto, () -> {
			long t = now[0];
			now[0] += CALIBRATION_BLOCK_HASHES;
			return t;
		});
		assertEquals("one block per clock tick of as many milliseconds",
				1000.0, c.hashesPerSecond(), 0.001);
		assertTrue(c.affordable(0));
		assertTrue("about 16 s, 33 s with the margin", c.affordable(14));
		assertFalse("about 66 s, over the budget with the margin",
				c.affordable(16));
		assertFalse(c.affordable(SENDER_DIFFICULTY_CAP + 1));
	}

	@Test
	public void theMeasurementWarmsUpFirstAndIsBoundedInTime() {
		long[] now = {0};
		int[] reads = {0};
		ContactRequestCrypto c = new ContactRequestCrypto(crypto, () -> {
			reads[0]++;
			long t = now[0];
			now[0] += 10;
			return t;
		});
		double rate = c.hashesPerSecond();
		assertEquals("one block per 10 ms tick",
				CALIBRATION_BLOCK_HASHES * 100.0, rate, 0.001);
		assertTrue("warm-up and measurement each take about 30 ticks, read"
				+ " the clock " + reads[0] + " times", reads[0] <= 70);
		assertEquals(1, c.calibrationCount());
	}

	@Test
	public void theHashRateIsMeasuredOncePerInstance() {
		ContactRequestCrypto c = new ContactRequestCrypto(crypto);
		assertEquals(0, c.calibrationCount());
		c.affordable(0);
		assertEquals("level 0 needs no measurement", 0, c.calibrationCount());
		c.affordable(16);
		c.affordable(20);
		c.hashesPerSecond();
		assertEquals(1, c.calibrationCount());
		assertTrue(c.hashesPerSecond() > 0);
	}
}

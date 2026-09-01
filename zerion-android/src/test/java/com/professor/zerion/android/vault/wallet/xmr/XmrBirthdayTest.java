package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

public class XmrBirthdayTest {

	private static long ms(int y, int mo, int d) {
		return ZonedDateTime.of(y, mo, d, 0, 0, 0, 0, ZoneOffset.UTC)
				.toInstant().toEpochMilli();
	}

	@Test
	public void aug27IsBeforeTheAug29Transaction() {
		long h = XmrBirthday.heightForDate(ms(2026, 8, 27));
		assertTrue("must be a plausible recent height", h > 3_700_000L);
		assertTrue("must be strictly before the ~3,750,620 height of an Aug-29 "
				+ "transaction", h < 3_750_620L);
	}

	@Test
	public void neverLaterThanTheNaiveExtrapolation() {
		long checkpointMs = 1787875200000L;
		long checkpointHeight = 3_749_900L;
		for (long day = -800; day <= 3650; day += 37) {
			long date = checkpointMs + day * 86_400_000L;
			long naive = checkpointHeight + (date - checkpointMs) / 120_000L;
			long est = XmrBirthday.heightForDate(date);
			assertTrue("estimate must never exceed the no-margin extrapolation "
					+ "(false-early invariant) at day " + day,
					est <= naive);
		}
	}

	@Test
	public void marginGrowsWithDistanceSoDistantDatesAreMoreConservative() {
		long checkpointMs = 1787875200000L;
		long near = checkpointMs + 30L * 86_400_000L;
		long far = checkpointMs + 3650L * 86_400_000L;
		long naiveNear = 3_749_900L + (near - checkpointMs) / 120_000L;
		long naiveFar = 3_749_900L + (far - checkpointMs) / 120_000L;
		long marginNear = naiveNear - XmrBirthday.heightForDate(near);
		long marginFar = naiveFar - XmrBirthday.heightForDate(far);
		assertTrue("a date years out carries a larger safety margin than one a "
				+ "month out", marginFar > marginNear);
	}

	@Test
	public void monotonicNonDecreasingInDate() {
		long prev = -1;
		for (long day = -400; day <= 4000; day += 15) {
			long date = 1787875200000L + day * 86_400_000L;
			long h = XmrBirthday.heightForDate(date);
			assertTrue("a later date never yields an earlier start", h >= prev);
			prev = h;
		}
	}

	@Test
	public void veryAncientDatesFloorAtGenesis() {
		assertEquals(0L, XmrBirthday.heightForDate(ms(2005, 1, 1)));
	}

	@Test
	public void oldDateExtrapolatesToAPlausibleEarlyHeight() {
		long h = XmrBirthday.heightForDate(ms(2019, 1, 1));
		assertTrue("conservatively early", h < 1_720_000L);
		assertTrue("but not genesis for a date years after launch", h > 0L);
	}

	@Test
	public void newWalletBirthdayIsNearTheLatestCheckpoint() {
		long h = XmrBirthday.estimateHeight(ms(2026, 8, 31));
		assertTrue(h >= 3_748_460L);
		assertTrue(h <= 3_752_000L);
	}
}

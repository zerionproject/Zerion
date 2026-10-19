package com.professor.zerion.android.util;

import android.content.Context;
import android.text.format.DateUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ChannelHourWordingTest {

	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60L * MINUTE;
	private static final long DAY = 24L * HOUR;

	private final Context ctx = RuntimeEnvironment.getApplication();

	private static long todayAt(int hour, int minute) {
		Calendar c = Calendar.getInstance();
		c.set(Calendar.HOUR_OF_DAY, hour);
		c.set(Calendar.MINUTE, minute);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		return c.getTimeInMillis();
	}

	private static long hourOf(long at) {
		return Math.floorDiv(at, HOUR) * HOUR;
	}

	private String minuteShown(long at) {
		return DateUtils.formatDateTime(ctx, at, DateUtils.FORMAT_SHOW_TIME);
	}

	@Test
	public void aTimeWrittenWithinAnHourReadsAsThatHourNotItsMinute() {
		long written = todayAt(10, 48);
		long kept = hourOf(written);
		String sending = UiUtils.formatChannelHour(ctx, written);
		String stored = UiUtils.formatChannelHour(ctx, kept);
		assertEquals("a sending item and the stored one read alike", stored,
				sending);
		assertTrue(stored, stored.startsWith("around "));
		assertFalse("the minute it was written is shown: " + sending,
				sending.contains(minuteShown(written)));
		assertFalse(sending, sending.contains("48"));
	}

	@Test
	public void anItemFromAnotherDayNamesTheDayAndTheHour() {
		long kept = hourOf(todayAt(10, 0) - 3L * DAY);
		long now = System.currentTimeMillis();
		String shown = HourTimes.format(ctx, kept, now);
		boolean sameYear = yearOf(kept) == yearOf(now);
		String day = DateUtils.formatDateTime(ctx, kept,
				DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
						| (sameYear ? DateUtils.FORMAT_NO_YEAR
						: DateUtils.FORMAT_SHOW_YEAR));
		assertEquals(day + ", around " + DateUtils.formatDateTime(ctx, kept,
				DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_ALL),
				shown);
		assertEquals(shown, UiUtils.formatChannelHour(ctx, kept));
	}

	@Test
	public void anItemFromLateYesterdayIsNotShownAsIfItWereToday() {
		long kept = hourOf(todayAt(0, 30) - HOUR);
		String shown = HourTimes.format(ctx, kept, todayAt(9, 0));
		assertTrue(shown, shown.contains(DateUtils.formatDateTime(ctx, kept,
				DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
						| DateUtils.FORMAT_NO_YEAR)));
	}

	@Test
	public void anItemFromAnotherYearNamesTheYear() {
		long now = System.currentTimeMillis();
		long kept = hourOf(now - 400L * DAY);
		String shown = HourTimes.format(ctx, kept, now);
		assertTrue(shown, shown.contains(String.valueOf(yearOf(kept))));
		assertTrue(shown, shown.contains("around "));
	}

	@Test
	public void theDayFormNamesTheDayEvenForToday() {
		long written = todayAt(10, 48);
		String shown = HourTimes.withDay(ctx, written,
				System.currentTimeMillis());
		assertTrue(shown, shown.startsWith(DateUtils.formatDateTime(ctx,
				written, DateUtils.FORMAT_SHOW_DATE
						| DateUtils.FORMAT_ABBREV_MONTH
						| DateUtils.FORMAT_NO_YEAR) + ", around "));
		assertEquals(HourTimes.withDay(ctx, hourOf(written),
				System.currentTimeMillis()), shown);
		assertFalse(shown, shown.contains(minuteShown(written)));
	}

	@Test
	public void theHourIsTheOneTheChannelKeeps() {
		long written = todayAt(10, 48) + 17_000L;
		assertEquals(written / HOUR * HOUR, HourTimes.hourOf(written));
		assertEquals(-HOUR, HourTimes.hourOf(-1L));
	}

	private static int yearOf(long at) {
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(at);
		return c.get(Calendar.YEAR);
	}
}

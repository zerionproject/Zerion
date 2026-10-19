package com.professor.zerion.android.util;

import android.content.Context;
import android.text.format.DateUtils;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Calendar;

import static android.text.format.DateUtils.FORMAT_ABBREV_ALL;
import static android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
import static android.text.format.DateUtils.FORMAT_NO_YEAR;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.text.format.DateUtils.FORMAT_SHOW_YEAR;

@NotNullByDefault
public final class HourTimes {

	public static final long HOUR_MS = 60L * 60L * 1000L;

	private HourTimes() {
	}

	public static long hourOf(long timeMs) {
		return Math.floorDiv(timeMs, HOUR_MS) * HOUR_MS;
	}

	public static String format(Context ctx, long timeMs, long now) {
		long hour = hourOf(timeMs);
		if (sameDay(hour, now)) return around(ctx, hour);
		return withDay(ctx, hour, now);
	}

	public static String around(Context ctx, long timeMs) {
		return ctx.getString(R.string.channels_time_around,
				clock(ctx, hourOf(timeMs)));
	}

	public static String withDay(Context ctx, long timeMs, long now) {
		long hour = hourOf(timeMs);
		int flags = FORMAT_SHOW_DATE | FORMAT_ABBREV_MONTH
				| (sameYear(hour, now) ? FORMAT_NO_YEAR : FORMAT_SHOW_YEAR);
		return ctx.getString(R.string.channels_time_day_around,
				DateUtils.formatDateTime(ctx, hour, flags), clock(ctx, hour));
	}

	private static String clock(Context ctx, long hour) {
		return DateUtils.formatDateTime(ctx, hour,
				FORMAT_SHOW_TIME | FORMAT_ABBREV_ALL);
	}

	private static boolean sameDay(long a, long b) {
		Calendar x = calendar(a);
		Calendar y = calendar(b);
		return x.get(Calendar.YEAR) == y.get(Calendar.YEAR)
				&& x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
	}

	private static boolean sameYear(long a, long b) {
		return calendar(a).get(Calendar.YEAR) == calendar(b).get(Calendar.YEAR);
	}

	private static Calendar calendar(long at) {
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(at);
		return c;
	}
}

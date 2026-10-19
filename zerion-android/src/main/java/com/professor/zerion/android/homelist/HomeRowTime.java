package com.professor.zerion.android.homelist;

import android.content.Context;
import android.text.format.DateUtils;

import com.professor.zerion.android.util.HourTimes;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Calendar;

import static android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
import static android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY;
import static android.text.format.DateUtils.FORMAT_NO_YEAR;
import static android.text.format.DateUtils.FORMAT_NUMERIC_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY;
import static android.text.format.DateUtils.FORMAT_SHOW_YEAR;

@NotNullByDefault
final class HomeRowTime {

	private static final int WEEKDAY_SPAN_DAYS = 6;

	private HomeRowTime() {
	}

	static String format(Context ctx, long time, long now) {
		return format(ctx, time, now, false);
	}

	static String format(Context ctx, long time, long now,
			boolean hourPrecision) {
		if (time <= 0) return "";
		Calendar then = Calendar.getInstance();
		then.setTimeInMillis(time);
		Calendar today = Calendar.getInstance();
		today.setTimeInMillis(now);
		boolean dayOnly = hourPrecision && isMidnight(then);
		if (sameDay(then, today)) {
			if (hourPrecision && !dayOnly) return HourTimes.around(ctx, time);
			if (!dayOnly) {
				return DateUtils.formatDateTime(ctx, time, FORMAT_SHOW_TIME);
			}
			return DateUtils.formatDateTime(ctx, time,
					FORMAT_SHOW_DATE | FORMAT_NO_YEAR | FORMAT_ABBREV_MONTH);
		}
		Calendar weekStart = (Calendar) today.clone();
		weekStart.set(Calendar.HOUR_OF_DAY, 0);
		weekStart.set(Calendar.MINUTE, 0);
		weekStart.set(Calendar.SECOND, 0);
		weekStart.set(Calendar.MILLISECOND, 0);
		weekStart.add(Calendar.DAY_OF_YEAR, -WEEKDAY_SPAN_DAYS);
		if (time < now && time >= weekStart.getTimeInMillis()) {
			return DateUtils.formatDateTime(ctx, time,
					FORMAT_SHOW_WEEKDAY | FORMAT_ABBREV_WEEKDAY);
		}
		if (then.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
			return DateUtils.formatDateTime(ctx, time,
					FORMAT_SHOW_DATE | FORMAT_NO_YEAR | FORMAT_ABBREV_MONTH);
		}
		return DateUtils.formatDateTime(ctx, time,
				FORMAT_SHOW_DATE | FORMAT_SHOW_YEAR | FORMAT_NUMERIC_DATE);
	}

	private static boolean isMidnight(Calendar c) {
		return c.get(Calendar.HOUR_OF_DAY) == 0 && c.get(Calendar.MINUTE) == 0;
	}

	private static boolean sameDay(Calendar a, Calendar b) {
		return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
				&& a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
	}
}

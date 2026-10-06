package com.professor.zerion.android.grouptr;

import android.content.Context;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
final class GroupTrTimerLabels {

	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60L * MINUTE;
	private static final long DAY = 24L * HOUR;

	private GroupTrTimerLabels() {
	}

	static String label(Context ctx, long ms) {
		if (ms <= 0L) return ctx.getString(R.string.grouptr_ttl_off_label);
		if (ms == 5L * MINUTE) return ctx.getString(R.string.grouptr_ttl_5min);
		if (ms == HOUR) return ctx.getString(R.string.grouptr_ttl_1hr);
		if (ms == DAY) return ctx.getString(R.string.grouptr_ttl_1day);
		if (ms == 7L * DAY) return ctx.getString(R.string.grouptr_ttl_7days);
		if (ms == 30L * DAY) return ctx.getString(R.string.grouptr_ttl_30days);
		long minutes = Math.max(1L, ms / MINUTE);
		return ctx.getResources().getQuantityString(
				R.plurals.grouptr_ttl_minutes, (int) Math.min(minutes,
						Integer.MAX_VALUE), minutes);
	}
}

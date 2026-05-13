package com.professor.zerion.android.grouptr;

import android.content.Context;
import android.content.SharedPreferences;

import org.briarproject.bramble.util.StringUtils;

class GroupTrLocalPrefs {

	private static final String PREFS = "grouptr_local";
	private static final String KEY_SCREENSHOT_PREFIX = "ss_";
	private static final String KEY_TTL_PREFIX = "ttl_";

	private static SharedPreferences prefs(Context ctx) {
		return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	static void saveScreenshotBlocked(Context ctx, byte[] gid, boolean v) {
		prefs(ctx).edit().putBoolean(
				KEY_SCREENSHOT_PREFIX + StringUtils.toHexString(gid), v)
				.apply();
	}

	static boolean isScreenshotBlocked(Context ctx, byte[] gid) {
		return prefs(ctx).getBoolean(
				KEY_SCREENSHOT_PREFIX + StringUtils.toHexString(gid), false);
	}

	static void saveDisappearingTtl(Context ctx, byte[] gid, long seconds) {
		prefs(ctx).edit().putLong(
				KEY_TTL_PREFIX + StringUtils.toHexString(gid), seconds)
				.apply();
	}

	static long getDisappearingTtl(Context ctx, byte[] gid) {
		return prefs(ctx).getLong(
				KEY_TTL_PREFIX + StringUtils.toHexString(gid), 0L);
	}
}

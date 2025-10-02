package com.professor.zerion.android;

import com.professor.zerion.BuildConfig;

import static java.util.concurrent.TimeUnit.DAYS;
import static com.professor.zerion.BuildConfig.BuildTimestamp;

public interface TestingConstants {

	/**
	 * Whether this is a debug build.
	 */
	boolean IS_DEBUG_BUILD = BuildConfig.DEBUG;

	/**
	 * Whether to prevent screenshots from being taken. Setting this to true
	 * prevents Recent Apps from storing screenshots of private information.
	 * Unfortunately this also prevents the user from taking screenshots
	 * intentionally.
	 */
	boolean PREVENT_SCREENSHOTS = !IS_DEBUG_BUILD;

	/**
	 * Debug builds expire after 90 days.
	 */
	long EXPIRY_DATE = IS_DEBUG_BUILD ?
			BuildTimestamp + DAYS.toMillis(90) : Long.MAX_VALUE;
}

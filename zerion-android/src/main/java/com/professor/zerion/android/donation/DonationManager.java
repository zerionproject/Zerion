package com.professor.zerion.android.donation;

import android.content.SharedPreferences;

import com.professor.zerion.android.AppModule.UiPrefs;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
public class DonationManager {

	private static final String PREF_LAST_DONATION_PROMPT = "donation_last_prompt";
	private static final String PREF_DONATION_PROMPT_COUNT = "donation_prompt_count";
	private static final String PREF_NEXT_CHECK_DAY = "donation_next_check_day";
	private static final String PREF_FIRST_LAUNCH_DAY = "donation_first_launch_day";
	private static final long MIN_DAYS_BETWEEN_PROMPTS = 90;
	private static final long INSTALL_GRACE_DAYS = 14;
	private static final float DAILY_SHOW_PROBABILITY = 0.02f;
	private static final int MAX_LIFETIME_PROMPTS = 5;

	private final SharedPreferences prefs;
	private final Random random;
	private volatile int cachedCheckDay = -1;
	private volatile boolean cachedResult = false;

	@Inject
	public DonationManager(@UiPrefs SharedPreferences prefs) {
		this.prefs = prefs;
		this.random = new Random();
	}

	public boolean shouldShowDonationDialog() {
		long now = System.currentTimeMillis();
		int currentDay = (int) TimeUnit.MILLISECONDS.toDays(now);
		if (currentDay == cachedCheckDay) {
			return cachedResult;
		}
		int firstLaunchDay = prefs.getInt(PREF_FIRST_LAUNCH_DAY, -1);
		if (firstLaunchDay < 0) {
			prefs.edit().putInt(PREF_FIRST_LAUNCH_DAY, currentDay).apply();
			firstLaunchDay = currentDay;
		}
		if (currentDay - firstLaunchDay < INSTALL_GRACE_DAYS) {
			cachedCheckDay = currentDay;
			cachedResult = false;
			return false;
		}
		if (prefs.getInt(PREF_DONATION_PROMPT_COUNT, 0)
				>= MAX_LIFETIME_PROMPTS) {
			cachedCheckDay = currentDay;
			cachedResult = false;
			return false;
		}
		long lastPrompt = prefs.getLong(PREF_LAST_DONATION_PROMPT, 0);
		int nextCheckDay = prefs.getInt(PREF_NEXT_CHECK_DAY, 0);
		if (currentDay <= nextCheckDay && lastPrompt > 0) {
			cachedCheckDay = currentDay;
			cachedResult = false;
			return false;
		}
		if (lastPrompt > 0) {
			long daysSinceLastPrompt =
					TimeUnit.MILLISECONDS.toDays(now - lastPrompt);
			if (daysSinceLastPrompt < MIN_DAYS_BETWEEN_PROMPTS) {
				cachedCheckDay = currentDay;
				cachedResult = false;
				return false;
			}
		}
		boolean shouldShow = random.nextFloat() < DAILY_SHOW_PROBABILITY;

		cachedCheckDay = currentDay;
		cachedResult = shouldShow;
		if (!shouldShow) {
			prefs.edit()
					.putInt(PREF_NEXT_CHECK_DAY, currentDay)
					.apply();
		}
		return shouldShow;
	}

	public void onDialogShown() {
		long now = System.currentTimeMillis();
		int currentDay = (int) TimeUnit.MILLISECONDS.toDays(now);
		int promptCount = prefs.getInt(PREF_DONATION_PROMPT_COUNT, 0);

		prefs.edit()
				.putLong(PREF_LAST_DONATION_PROMPT, now)
				.putInt(PREF_DONATION_PROMPT_COUNT, promptCount + 1)
				.putInt(PREF_NEXT_CHECK_DAY, currentDay)
				.apply();
	}

	public int getPromptCount() {
		return prefs.getInt(PREF_DONATION_PROMPT_COUNT, 0);
	}

	public void resetForTesting() {
		prefs.edit()
				.remove(PREF_LAST_DONATION_PROMPT)
				.remove(PREF_DONATION_PROMPT_COUNT)
				.remove(PREF_NEXT_CHECK_DAY)
				.apply();
	}
}

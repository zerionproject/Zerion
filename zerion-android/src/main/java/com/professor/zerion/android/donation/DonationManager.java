package com.professor.zerion.android.donation;

import android.content.SharedPreferences;

import com.professor.zerion.android.AppModule.UiPrefs;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages donation dialog display timing.
 * Shows the dialog randomly once per month.
 */
@Singleton
@NotNullByDefault
public class DonationManager {

	private static final String PREF_LAST_DONATION_PROMPT = "donation_last_prompt";
	private static final String PREF_DONATION_PROMPT_COUNT = "donation_prompt_count";
	private static final String PREF_NEXT_CHECK_DAY = "donation_next_check_day";

	// Show dialog once every 30 days at most
	private static final long MIN_DAYS_BETWEEN_PROMPTS = 30;

	// Random chance per day (after minimum period) - roughly 1 in 7 days
	private static final float DAILY_SHOW_PROBABILITY = 0.15f;

	private final SharedPreferences prefs;
	private final Random random;

	@Inject
	public DonationManager(@UiPrefs SharedPreferences prefs) {
		this.prefs = prefs;
		this.random = new Random();
	}

	/**
	 * Check if the donation dialog should be shown.
	 * Returns true randomly once a month on average.
	 */
	public boolean shouldShowDonationDialog() {
		long now = System.currentTimeMillis();
		long lastPrompt = prefs.getLong(PREF_LAST_DONATION_PROMPT, 0);
		int nextCheckDay = prefs.getInt(PREF_NEXT_CHECK_DAY, 0);

		// Calculate current day number (for tracking which day to check)
		int currentDay = (int) TimeUnit.MILLISECONDS.toDays(now);

		// If we've already checked today, don't check again
		if (currentDay <= nextCheckDay && lastPrompt > 0) {
			return false;
		}

		// Check if minimum time has passed since last prompt
		long daysSinceLastPrompt = TimeUnit.MILLISECONDS.toDays(now - lastPrompt);
		if (daysSinceLastPrompt < MIN_DAYS_BETWEEN_PROMPTS) {
			return false;
		}

		// Random chance to show dialog today
		boolean shouldShow = random.nextFloat() < DAILY_SHOW_PROBABILITY;

		if (shouldShow) {
			// Will show dialog, timestamp will be set in onDialogShown()
			return true;
		} else {
			// Not showing today, record that we checked
			prefs.edit()
					.putInt(PREF_NEXT_CHECK_DAY, currentDay)
					.apply();
			return false;
		}
	}

	/**
	 * Call this when the donation dialog is shown.
	 */
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

	/**
	 * Get the number of times the donation dialog has been shown.
	 */
	public int getPromptCount() {
		return prefs.getInt(PREF_DONATION_PROMPT_COUNT, 0);
	}

	/**
	 * Reset donation prompts (for testing).
	 */
	public void resetForTesting() {
		prefs.edit()
				.remove(PREF_LAST_DONATION_PROMPT)
				.remove(PREF_DONATION_PROMPT_COUNT)
				.remove(PREF_NEXT_CHECK_DAY)
				.apply();
	}
}

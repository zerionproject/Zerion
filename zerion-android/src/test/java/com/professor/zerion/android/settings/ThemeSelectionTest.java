package com.professor.zerion.android.settings;

import androidx.appcompat.app.AppCompatDelegate;

import com.professor.zerion.android.util.UiUtils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ThemeSelectionTest {

	private static final String LIGHT = "light";
	private static final String DARK = "dark";
	private static final String AMOLED = "amoled";
	private static final String SYSTEM = "system";

	private int mode(String theme) {
		return UiUtils.nightModeFor(theme, LIGHT, DARK, AMOLED);
	}

	@Test
	public void lightSelectsDayMode() {
		assertEquals(AppCompatDelegate.MODE_NIGHT_NO, mode(LIGHT));
	}

	@Test
	public void darkAndAmoledSelectNightMode() {
		assertEquals(AppCompatDelegate.MODE_NIGHT_YES, mode(DARK));
		assertEquals(AppCompatDelegate.MODE_NIGHT_YES, mode(AMOLED));
	}

	@Test
	public void systemFollowsTheDevice() {
		assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, mode(SYSTEM));
	}

	@Test
	public void darkToLightToDarkTransitions() {
		assertEquals(AppCompatDelegate.MODE_NIGHT_YES, mode(DARK));
		assertEquals(AppCompatDelegate.MODE_NIGHT_NO, mode(LIGHT));
		assertEquals(AppCompatDelegate.MODE_NIGHT_YES, mode(DARK));
	}

	@Test
	public void unknownFallsBackToSystem() {
		assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, mode("nonsense"));
	}
}

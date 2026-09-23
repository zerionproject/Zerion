package com.professor.zerion.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The pre-unlock store holds exactly the language, theme and text size, round
 * trips them, and exposes no preferences object through which another key
 * could be added.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class EarlyPrefsTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void theThreeDisplaySettingsRoundTrip() {
		Context ctx = RuntimeEnvironment.getApplication();
		assertEquals("default", EarlyPrefs.language(ctx));
		assertEquals("the application sets the default theme at start",
				ctx.getString(com.professor.zerion.R.string.pref_theme_dark_value),
				EarlyPrefs.theme(ctx));
		assertEquals(3, EarlyPrefs.guiTextSize(ctx, 3));
		EarlyPrefs.setLanguage(ctx, "nl");
		EarlyPrefs.setTheme(ctx, "amoled");
		EarlyPrefs.setGuiTextSize(ctx, 1);
		assertEquals("nl", EarlyPrefs.language(ctx));
		assertEquals("amoled", EarlyPrefs.theme(ctx));
		assertEquals(1, EarlyPrefs.guiTextSize(ctx, 3));
	}

	@Test
	public void noPublicMethodHandsOutTheStore() {
		for (Method m : EarlyPrefs.class.getDeclaredMethods()) {
			if (!Modifier.isPublic(m.getModifiers())) continue;
			assertFalse(m.getName(), SharedPreferences.class
					.isAssignableFrom(m.getReturnType()));
			for (Class<?> p : m.getParameterTypes()) {
				assertFalse(m.getName(),
						SharedPreferences.class.isAssignableFrom(p));
			}
		}
	}
}

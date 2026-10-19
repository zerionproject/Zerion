package com.professor.zerion.android.activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.EarlyPrefs;
import com.professor.zerion.android.api.ScreenFilterMonitor;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.settings.DisplayFragment;
import com.professor.zerion.android.util.UiUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.settings.DisplayFragment.PREF_THEME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AmoledThemeTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class Probe extends BaseActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			screenFilterMonitor = mock(ScreenFilterMonitor.class);
		}

		@Override
		public void onCreate(@Nullable Bundle state) {
			setTheme(R.style.ZerionTheme_NoActionBar);
			super.onCreate(state);
		}
	}

	@Nullable
	private String storedTheme;

	private static Context app() {
		return RuntimeEnvironment.getApplication();
	}

	private static String value(int res) {
		return app().getString(res);
	}

	private static SharedPreferences uiPrefs() {
		return AppModule.getAndroidComponent(app()).uiPreferences();
	}

	@Before
	public void setUp() {
		storedTheme = EarlyPrefs.theme(app());
	}

	@After
	public void tearDown() {
		EarlyPrefs.setTheme(app(), storedTheme != null ? storedTheme
				: value(R.string.pref_theme_dark_value));
		uiPrefs().edit().remove(PREF_THEME).commit();
	}

	private static int background(String theme) {
		EarlyPrefs.setTheme(app(), theme);
		Probe a = Robolectric.buildActivity(Probe.class).create().get();
		return UiUtils.resolveColorAttribute(a, R.attr.zerionColorBackground);
	}

	@Test
	public void choosingAmoledInDisplaySettingsAppliesTheOverlay() {
		EarlyPrefs.setTheme(app(), value(R.string.pref_theme_amoled_value));
		assertTrue(BaseActivity.wantsAmoledOverlay(app()));
	}

	@Test
	public void theOtherThemesKeepTheirSurfaces() {
		for (int theme : new int[] {R.string.pref_theme_dark_value,
				R.string.pref_theme_light_value}) {
			EarlyPrefs.setTheme(app(), value(theme));
			assertFalse(value(theme), BaseActivity.wantsAmoledOverlay(app()));
		}
	}

	@Test
	@Config(qualifiers = "night")
	public void aScreenOpenedWithAmoledChosenIsBlack() {
		assertEquals(0xFF000000, background(
				value(R.string.pref_theme_amoled_value)));
		assertEquals(0xFF0F1014, background(
				value(R.string.pref_theme_dark_value)));
	}

	@Test
	@Config(qualifiers = "night")
	public void anAmoledChoiceFromTheOldSettingsStoreIsKept() {
		uiPrefs().edit().putString(PREF_THEME,
				value(R.string.pref_theme_amoled_value)).commit();
		assertEquals(0xFF000000, background(
				value(R.string.pref_theme_dark_value)));
		assertEquals(value(R.string.pref_theme_amoled_value),
				EarlyPrefs.theme(app()));
	}

	@Test
	public void aThemeChosenInTheNewStoreIsNeverOverridden() {
		uiPrefs().edit().putString(PREF_THEME,
				value(R.string.pref_theme_amoled_value)).commit();
		EarlyPrefs.setTheme(app(), value(R.string.pref_theme_light_value));
		BaseActivity.moveLegacyTheme(app(), uiPrefs());
		assertEquals(value(R.string.pref_theme_light_value),
				EarlyPrefs.theme(app()));
	}

	@Test
	public void aDarkThemePickedInDisplaySettingsIsNeverReplaced() {
		uiPrefs().edit().putString(PREF_THEME,
				value(R.string.pref_theme_amoled_value)).commit();
		DisplayFragment.storeChosenTheme(app(),
				value(R.string.pref_theme_dark_value));
		BaseActivity.moveLegacyTheme(app(), uiPrefs());
		assertEquals(value(R.string.pref_theme_dark_value),
				EarlyPrefs.theme(app()));
		assertFalse(BaseActivity.wantsAmoledOverlay(app()));
	}

	@Test
	public void theOldChoiceIsMovedOnlyOnce() {
		uiPrefs().edit().putString(PREF_THEME,
				value(R.string.pref_theme_amoled_value)).commit();
		EarlyPrefs.setTheme(app(), value(R.string.pref_theme_dark_value));
		BaseActivity.moveLegacyTheme(app(), uiPrefs());
		assertEquals(value(R.string.pref_theme_amoled_value),
				EarlyPrefs.theme(app()));
		EarlyPrefs.setTheme(app(), value(R.string.pref_theme_dark_value));
		BaseActivity.moveLegacyTheme(app(), uiPrefs());
		assertEquals(value(R.string.pref_theme_dark_value),
				EarlyPrefs.theme(app()));
	}
}

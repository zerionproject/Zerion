package com.professor.zerion.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class TestApplicationIsolationTest {

	private final Application app = RuntimeEnvironment.getApplication();

	@Test
	public void everyTestRunsInTheTestApplication() {
		assertTrue(app.getClass().getName(),
				app instanceof TestZerionApplicationImpl);
	}

	@Test
	public void theStartupHasFinishedBeforeTheTestBegins() throws Exception {
		assertTrue(((ZerionApplicationImpl) app).awaitEagerSingletons(1));
	}

	@Test
	public void theSettingsOfOneTestAreStoredInItsOwnApplication() {
		assertSettingsStoredInThisApplication();
	}

	@Test
	public void theSettingsOfTheNextTestAreStoredInItsOwnApplication() {
		assertSettingsStoredInThisApplication();
	}

	private void assertSettingsStoredInThisApplication() {
		SharedPreferences ui = app.getSharedPreferences("ui_prefs_v2",
				Context.MODE_PRIVATE);
		int before = ui.getAll().size();
		assertTrue(AppModule.getUiPrefs().edit()
				.putInt("isolation_probe", 1).commit());
		assertEquals(before + 1, ui.getAll().size());
		assertFalse("the theme chosen at start is in another application",
				app.getSharedPreferences("early_ui_prefs_v2",
						Context.MODE_PRIVATE).getAll().isEmpty());
	}
}

package com.professor.zerion.android.panic;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Map;
import java.util.TreeMap;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PanicSettingsTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class Host extends AppCompatActivity {

		@Override
		protected void onCreate(Bundle state) {
			setTheme(R.style.ZerionTheme);
			super.onCreate(state);
		}
	}

	private final Application app = RuntimeEnvironment.getApplication();

	private SharedPreferences encrypted() {
		return ((ZerionApplication) app).getApplicationComponent()
				.uiPreferences();
	}

	private SharedPreferences plain() {
		return PreferenceManager.getDefaultSharedPreferences(app);
	}

	private Map<String, ?> plainPanicKeys() {
		Map<String, Object> out = new TreeMap<>();
		for (Map.Entry<String, ?> e : plain().getAll().entrySet()) {
			if (e.getKey().startsWith("pref_key_")
					|| e.getKey().startsWith("panicResponder")) {
				out.put(e.getKey(), e.getValue());
			}
		}
		return out;
	}

	@Test
	public void theScreenShowsTheStateAPanicActsOn() {
		encrypted().edit()
				.putBoolean(PanicPreferencesFragment.KEY_LOCK, true)
				.putBoolean(PanicPreferencesFragment.KEY_PURGE, true)
				.commit();
		plain().edit()
				.putBoolean(PanicPreferencesFragment.KEY_LOCK, true)
				.putBoolean(PanicPreferencesFragment.KEY_PURGE, false)
				.commit();

		Host host = Robolectric.buildActivity(Host.class).setup().get();
		PanicPreferencesFragment f = new PanicPreferencesFragment();
		host.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, f).commitNow();
		shadowOf(Looper.getMainLooper()).idle();
		SwitchPreferenceCompat purge =
				f.findPreference(PanicPreferencesFragment.KEY_PURGE);
		assertEquals("erase on panic shown on, as stored",
				"erase on panic shown "
						+ (purge != null && purge.isChecked() ? "on" : "off")
						+ ", as stored");
	}

	@Test
	public void whatAnEarlierVersionLeftInThePlainFileIsMovedOrRemoved() {
		plain().edit()
				.putString("panicResponderTriggerPackageName",
						"org.example.panic")
				.putBoolean(PanicPreferencesFragment.KEY_LOCK, true)
				.putBoolean(PanicPreferencesFragment.KEY_PURGE, false)
				.commit();
		PanicSettingsStore.migrate(app, encrypted());
		assertEquals("plain panic keys {}, connected app org.example.panic",
				"plain panic keys " + plainPanicKeys() + ", connected app "
						+ new PanicSettingsStore(encrypted())
						.getTriggerPackageName());
	}
}

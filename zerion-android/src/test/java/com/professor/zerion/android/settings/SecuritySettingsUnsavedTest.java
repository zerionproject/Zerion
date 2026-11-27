package com.professor.zerion.android.settings;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Looper;
import android.widget.CompoundButton;

import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecuritySettingsUnsavedTest {

	static {
		TestAndroidKeyStore.register();
	}

	@After
	public void tearDown() throws Exception {
		setHolderFailed(false);
	}

	@Test
	public void aDroppedHardenedChoiceIsReportedNotShownAsDone() {
		SecurityFragment fragment = host();
		fragment.uiPrefs = new DroppingPrefs(fragment.uiPrefs);
		ShadowDialog.reset();
		fragment.requireView().findViewById(R.id.hardened_mode_card)
				.performClick();
		idle();
		AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(choices);
		choices.getListView().performItemClick(null, 1, 1);
		choices.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();
		assertEquals(notSaved(fragment), ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void aDroppedEraseAfterFailuresIsReportedNotShownAsOn() {
		SecurityFragment fragment = host();
		fragment.bruteForceProtection = new com.professor.zerion.android
				.login.BruteForceProtection(
				new DroppingPrefs(fragment.uiPrefs), fragment.accountManager);
		ShadowDialog.reset();
		CompoundButton wipe = fragment.requireView()
				.findViewById(R.id.wipe_on_failed_logins_switch);
		wipe.setPressed(true);
		wipe.performClick();
		wipe.setPressed(false);
		idle();
		AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(confirm);
		confirm.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();
		assertFalse(wipe.isChecked());
		assertEquals(notSaved(fragment), ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void eraseAfterFailuresIsRefusedWhileSettingsCannotBeSaved()
			throws Exception {
		SecurityFragment fragment = host();
		setHolderFailed(true);
		ShadowDialog.reset();
		CompoundButton wipe = fragment.requireView()
				.findViewById(R.id.wipe_on_failed_logins_switch);
		wipe.setPressed(true);
		wipe.performClick();
		idle();
		assertFalse(wipe.isChecked());
		assertNull("asked for a password for a change that cannot be kept",
				ShadowDialog.getLatestDialog());
		assertEquals(notSaved(fragment), ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void hardenedModeIsRefusedWhileSettingsCannotBeSaved()
			throws Exception {
		SecurityFragment fragment = host();
		setHolderFailed(true);
		ShadowDialog.reset();
		fragment.requireView().findViewById(R.id.hardened_mode_card)
				.performClick();
		idle();
		assertNull(ShadowDialog.getLatestDialog());
		assertEquals(notSaved(fragment), ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void screenshotProtectionIsRefusedWhileSettingsCannotBeSaved()
			throws Exception {
		SecurityFragment fragment = host();
		CompoundButton toggle = fragment.requireView()
				.findViewById(R.id.screenshot_protection_switch);
		boolean before = toggle.isChecked();
		setHolderFailed(true);
		toggle.setPressed(true);
		toggle.performClick();
		idle();
		assertEquals(before, toggle.isChecked());
		assertEquals(notSaved(fragment), ShadowToast.getTextOfLatestToast());
	}

	private static String notSaved(SecurityFragment fragment) {
		Context ctx = fragment.requireContext();
		return ctx.getString(R.string.security_settings_not_saved);
	}

	private static SecurityFragment host() {
		SettingsActivity activity = Robolectric
				.buildActivity(SettingsActivity.class).setup().get();
		SecurityFragment fragment = new SecurityFragment();
		activity.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, fragment).commitNow();
		idle();
		return fragment;
	}

	private static void setHolderFailed(boolean failed) throws Exception {
		Field f = Class.forName(AppModule.class.getName()
				+ "$SecurePrefsHolder").getDeclaredField("initFailed");
		f.setAccessible(true);
		f.set(null, failed);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static final class DroppingPrefs implements SharedPreferences {

		private final SharedPreferences reads;

		private DroppingPrefs(SharedPreferences reads) {
			this.reads = reads;
		}

		@Override
		public Map<String, ?> getAll() {
			return reads.getAll();
		}

		@Nullable
		@Override
		public String getString(String key, @Nullable String defValue) {
			return reads.getString(key, defValue);
		}

		@Nullable
		@Override
		public Set<String> getStringSet(String key,
				@Nullable Set<String> defValues) {
			return reads.getStringSet(key, defValues);
		}

		@Override
		public int getInt(String key, int defValue) {
			return reads.getInt(key, defValue);
		}

		@Override
		public long getLong(String key, long defValue) {
			return reads.getLong(key, defValue);
		}

		@Override
		public float getFloat(String key, float defValue) {
			return reads.getFloat(key, defValue);
		}

		@Override
		public boolean getBoolean(String key, boolean defValue) {
			return reads.getBoolean(key, defValue);
		}

		@Override
		public boolean contains(String key) {
			return reads.contains(key);
		}

		@Override
		public Editor edit() {
			return new Editor() {
				@Override
				public Editor putString(String key, @Nullable String value) {
					return this;
				}

				@Override
				public Editor putStringSet(String key,
						@Nullable Set<String> values) {
					return this;
				}

				@Override
				public Editor putInt(String key, int value) {
					return this;
				}

				@Override
				public Editor putLong(String key, long value) {
					return this;
				}

				@Override
				public Editor putFloat(String key, float value) {
					return this;
				}

				@Override
				public Editor putBoolean(String key, boolean value) {
					return this;
				}

				@Override
				public Editor remove(String key) {
					return this;
				}

				@Override
				public Editor clear() {
					return this;
				}

				@Override
				public boolean commit() {
					return false;
				}

				@Override
				public void apply() {
				}
			};
		}

		@Override
		public void registerOnSharedPreferenceChangeListener(
				OnSharedPreferenceChangeListener listener) {
		}

		@Override
		public void unregisterOnSharedPreferenceChangeListener(
				OnSharedPreferenceChangeListener listener) {
		}
	}
}

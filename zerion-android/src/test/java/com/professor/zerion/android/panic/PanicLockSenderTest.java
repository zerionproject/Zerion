package com.professor.zerion.android.panic;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Looper;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PanicLockSenderTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String CONNECTED = "org.example.connectedpanic";
	private static final String OTHER = "org.example.otherfdroidapp";

	public static class Probe extends PanicResponderActivity {

		static final List<String> signOuts = new ArrayList<>();

		@Override
		protected void signOut(boolean removeFromRecentApps,
				boolean deleteAccount) {
			signOuts.add(deleteAccount ? "purge" : "lock");
		}
	}

	private final Application app = RuntimeEnvironment.getApplication();

	private SharedPreferences encrypted() {
		return ((ZerionApplication) app).getApplicationComponent()
				.uiPreferences();
	}

	private void installSignedByFDroid(String pkg) {
		PackageInfo info = new PackageInfo();
		info.packageName = pkg;
		info.signatures = new FDroidSignaturePin().getSignatures();
		shadowOf(app.getPackageManager()).installPackage(info);
	}

	private List<String> panicFrom(String caller) {
		Probe.signOuts.clear();
		Intent trigger = new Intent(app, Probe.class);
		trigger.setAction("info.guardianproject.panic.action.TRIGGER");
		ActivityController<Probe> c =
				Robolectric.buildActivity(Probe.class, trigger);
		shadowOf(c.get()).setCallingActivity(
				new ComponentName(caller, caller + ".Trigger"));
		c.create();
		shadowOf(Looper.getMainLooper()).idle();
		return new ArrayList<>(Probe.signOuts);
	}

	@Before
	public void setUp() {
		installSignedByFDroid(CONNECTED);
		installSignedByFDroid(OTHER);
		encrypted().edit()
				.putString(PanicSettingsStore.KEY_TRIGGER_PACKAGE, CONNECTED)
				.putBoolean(PanicPreferencesFragment.KEY_LOCK, true)
				.putBoolean(PanicPreferencesFragment.KEY_PURGE, false)
				.commit();
	}

	@Test
	public void theConnectedPanicAppCanLock() {
		List<String> expected = new ArrayList<>();
		expected.add("lock");
		assertEquals(expected, panicFrom(CONNECTED));
	}

	@Test
	public void anotherTrustedSignedAppCannotLock() {
		assertEquals(new ArrayList<String>(), panicFrom(OTHER));
	}

	@Test
	public void anotherTrustedSignedAppCannotPurge() {
		encrypted().edit()
				.putBoolean(PanicPreferencesFragment.KEY_PURGE, true)
				.commit();
		assertEquals(new ArrayList<String>(), panicFrom(OTHER));
	}
}

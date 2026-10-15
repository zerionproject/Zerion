package com.professor.zerion.android.security;

import android.app.Application;
import android.content.SharedPreferences;

import com.professor.zerion.android.AndroidComponent;
import com.professor.zerion.android.security.AntiForensics.UsbPanicState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class UsbPanicArmingTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = RuntimeEnvironment.getApplication();
	private final List<Boolean> created = new ArrayList<>();

	private SharedPreferences prefs(boolean on, boolean wipe) {
		SharedPreferences p = app.getSharedPreferences("usb-panic-test",
				android.content.Context.MODE_PRIVATE);
		p.edit().putBoolean(HardenedModeEvaluator.PREF_HARDENED_USB_PANIC, on)
				.putBoolean(HardenedModeEvaluator.PREF_HARDENED_USB_PANIC_WIPE,
						wipe)
				.commit();
		return p;
	}

	private AntiForensics.PanicAction responder(boolean wipe) {
		created.add(wipe);
		return new UsbPanicResponder(wipe, null);
	}

	private void grant() {
		shadowOf(app).grantPermissions(app.getPackageName()
				+ ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
	}

	@Test
	public void theSettingDecidesWhetherAndHowThePanicIsArmed() {
		grant();
		AntiForensics af = new AntiForensics(app);
		assertEquals(UsbPanicState.OFF,
				UsbPanic.armIfConfigured(prefs(false, false), af,
						this::responder));
		assertTrue(created.isEmpty());
		assertEquals(UsbPanicState.ARMED,
				UsbPanic.armIfConfigured(prefs(true, false), af,
						this::responder));
		assertEquals(UsbPanicState.ARMED,
				UsbPanic.armIfConfigured(prefs(true, true), af,
						this::responder));
		assertEquals("[false, true]", created.toString());
		assertEquals(UsbPanicState.OFF,
				UsbPanic.armIfConfigured(prefs(false, true), af,
						this::responder));
		assertEquals(UsbPanicState.OFF, af.getUsbPanicState());
	}

	@Test
	public void aRegistrationThatFailsIsReportedNotHidden() {
		AntiForensics af = new AntiForensics(app, (c, r, f) -> {
			throw new SecurityException("refused");
		}, Runnable::run);
		assertEquals(UsbPanicState.FAILED,
				UsbPanic.armIfConfigured(prefs(true, false), af,
						this::responder));
	}

	@Test
	public void aFreshProcessIsNotReportedAsProtectedUntilArmed() {
		AntiForensics fresh = new AntiForensics(app);
		assertEquals(UsbPanicState.OFF, fresh.getUsbPanicState());
		assertTrue("an enabled panic that is not armed yet shows as not active",
				com.professor.zerion.android.settings.SecurityFragmentAccess
						.usbPanicFailed(true, fresh.getUsbPanicState()));
	}

	@Test
	public void theAppArmsThePanicAtStartBeforeAnyScreenOpens() {
		grant();
		AndroidComponent component = getAndroidComponent(app);
		component.uiPreferences().edit()
				.putBoolean(HardenedModeEvaluator.PREF_HARDENED_USB_PANIC, true)
				.commit();
		try {
			com.professor.zerion.android.ZerionApplicationAccess
					.armAtStart(app, component);
			assertEquals(UsbPanicState.ARMED,
					component.antiForensics().getUsbPanicState());
		} finally {
			component.uiPreferences().edit()
					.putBoolean(HardenedModeEvaluator.PREF_HARDENED_USB_PANIC,
							false)
					.commit();
			component.antiForensics().disarmUsbPanic();
		}
	}
}

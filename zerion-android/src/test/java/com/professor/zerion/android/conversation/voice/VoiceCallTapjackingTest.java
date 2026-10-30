package com.professor.zerion.android.conversation.voice;

import android.Manifest;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.fragment.ScreenFilterDialogFragment;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.android.security.SecureDialogs;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.util.Collections;

import javax.annotation.Nullable;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallTapjackingTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = ApplicationProvider.getApplicationContext();
	private ActivityController<VoiceCallActivity> screen;
	private ServiceController<VoiceCallService> service;

	@After
	public void tearDown() {
		VoiceCallKeyHolder.clear();
		if (screen != null) {
			try {
				screen.destroy();
			} catch (RuntimeException ignored) {
			}
		}
		if (service != null) {
			try {
				service.destroy();
			} catch (RuntimeException ignored) {
			}
		}
	}

	private Intent incoming(Class<?> target) {
		Intent i = new Intent(app, target);
		i.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, 7);
		i.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, true);
		i.putExtra(VoiceCallActivity.EXTRA_CALL_ID, "zt-tapjack-call");
		return i;
	}

	private VoiceCallActivity openIncomingCall() {
		shadowOf(app).grantPermissions(
				Manifest.permission.HIDE_OVERLAY_WINDOWS);
		service = Robolectric.buildService(VoiceCallService.class,
				incoming(VoiceCallService.class)).create().startCommand(0, 1);
		shadowOf(Looper.getMainLooper()).idle();
		shadowOf(app).setComponentNameAndServiceForBindService(
				new ComponentName(app, VoiceCallService.class),
				service.get().onBind(null));
		screen = Robolectric.buildActivity(VoiceCallActivity.class,
				incoming(VoiceCallActivity.class)).setup();
		shadowOf(Looper.getMainLooper()).idle();
		return screen.get();
	}

	private static MotionEvent tap(boolean obscured) {
		long now = SystemClock.uptimeMillis();
		MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
		p.id = 0;
		p.toolType = MotionEvent.TOOL_TYPE_FINGER;
		MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
		c.x = 10f;
		c.y = 10f;
		return MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1,
				new MotionEvent.PointerProperties[] {p},
				new MotionEvent.PointerCoords[] {c}, 0, 0, 1f, 1f, 0, 0,
				InputDevice.SOURCE_TOUCHSCREEN,
				obscured ? MotionEvent.FLAG_WINDOW_IS_OBSCURED : 0);
	}

	@Test
	public void answerAndDeclineIgnoreCoveredTapsOnAndroid10() {
		VoiceCallActivity a = openIncomingCall();
		assertTrue(a.findViewById(R.id.accept_call_button)
				.getFilterTouchesWhenObscured());
		assertTrue(a.findViewById(R.id.decline_call_button)
				.getFilterTouchesWhenObscured());
	}

	@Test
	public void withoutOverlayAppsAndroid10LetsCoveredTapsThrough() {
		VoiceCallActivity a = openIncomingCall();
		View root = callRoot(a);
		assertTrue(root.onFilterTouchEventForSecurity(tap(true)));
		assertNull(shownWarning(a));
		assertTrue(a.findViewById(R.id.accept_call_button)
				.getFilterTouchesWhenObscured());
	}

	@Test
	public void withAnOverlayAppAndroid10RefusesCoveredTapsAndWarns() {
		installOverlayApp();
		VoiceCallActivity a = openIncomingCall();
		View root = callRoot(a);
		assertFalse(root.onFilterTouchEventForSecurity(tap(true)));
		assertTrue(root.onFilterTouchEventForSecurity(tap(false)));
		shadowOf(Looper.getMainLooper()).idle();
		assertNotNull("the overlay warning was not shown", shownWarning(a));
	}

	@Test
	@Config(sdk = 32)
	public void fromAndroid11CoveredTapsAreAlwaysRefused() {
		VoiceCallActivity a = openIncomingCall();
		assertFalse(callRoot(a).onFilterTouchEventForSecurity(tap(true)));
		shadowOf(Looper.getMainLooper()).idle();
		assertNotNull(shownWarning(a));
	}

	@Test
	public void withoutOverlayAppsAndroid10DialogsKeepTheirTaps() {
		assertFalse(dialogFilters());
	}

	@Test
	public void withAnOverlayAppAndroid10DialogsIgnoreCoveredTaps() {
		installOverlayApp();
		assertTrue(dialogFilters());
	}

	@Test
	@Config(sdk = 32)
	public void fromAndroid11DialogsAlwaysIgnoreCoveredTaps() {
		assertTrue(dialogFilters());
	}

	private static View callRoot(VoiceCallActivity a) {
		ViewGroup content = a.findViewById(android.R.id.content);
		return content.getChildAt(0);
	}

	@Nullable
	private static Fragment shownWarning(VoiceCallActivity a) {
		return a.getSupportFragmentManager()
				.findFragmentByTag(ScreenFilterDialogFragment.TAG);
	}

	private void installOverlayApp() {
		PackageInfo info = new PackageInfo();
		info.packageName = "org.example.overlay";
		info.applicationInfo = new ApplicationInfo();
		info.applicationInfo.packageName = info.packageName;
		info.applicationInfo.name = "Overlay";
		info.requestedPermissions =
				new String[] {Manifest.permission.SYSTEM_ALERT_WINDOW};
		shadowOf(app.getPackageManager()).installPackage(info);
		((ZerionApplication) app).getApplicationComponent()
				.screenFilterMonitor().allowApps(Collections.emptyList());
	}

	private static boolean dialogFilters() {
		FragmentActivity host = Robolectric.buildActivity(
				FragmentActivity.class).setup().get();
		host.setTheme(R.style.ZerionTheme_NoActionBar);
		AlertDialog d = new SecureAlertDialogBuilder(host)
				.setTitle("t")
				.setPositiveButton(android.R.string.ok, null)
				.create();
		d.show();
		SecureDialogs.applyHostPolicy(d);
		return d.getWindow().getDecorView().getFilterTouchesWhenObscured();
	}
}

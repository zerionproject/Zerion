package com.professor.zerion.android.grouptr;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.WindowManager;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.IdentityManager;

import java.util.ArrayList;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_SCREENSHOT_PROTECTION;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupScreenshotProtectionTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String GROUP_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	public static class ProbeConversation extends GroupTrConversationActivity {

		static boolean groupBlock;
		static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			GroupTrManager m = mock(GroupTrManager.class);
			try {
				when(m.isLocalScreenshotBlocked(any())).thenReturn(groupBlock);
			} catch (DbException e) {
				throw new AssertionError(e);
			}
			groupTrManager = m;
			identityManager = mock(IdentityManager.class, RETURNS_DEEP_STUBS);
			ioExecutor = io::add;
		}
	}

	private SharedPreferences uiPrefs() {
		ZerionApplication app = ApplicationProvider.getApplicationContext();
		return app.getApplicationComponent().uiPreferences();
	}

	@Before
	public void setUp() {
		ProbeConversation.io.clear();
	}

	@After
	public void tearDown() {
		uiPrefs().edit().remove(PREF_SCREENSHOT_PROTECTION).commit();
		ProbeConversation.io.clear();
	}

	private void appWideProtection(boolean on) {
		uiPrefs().edit().putBoolean(PREF_SCREENSHOT_PROTECTION, on).commit();
	}

	private static boolean secure(Activity a) {
		return (a.getWindow().getAttributes().flags
				& WindowManager.LayoutParams.FLAG_SECURE) != 0;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private ProbeConversation openGroup(boolean groupBlock) {
		ProbeConversation.groupBlock = groupBlock;
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		ActivityController<ProbeConversation> c =
				Robolectric.buildActivity(ProbeConversation.class, i).setup();
		idle();
		Runnable groupSettingLoad = ProbeConversation.io.get(0);
		groupSettingLoad.run();
		idle();
		return c.get();
	}

	@Test
	public void aGroupWithoutItsOwnBlockKeepsTheAppWideProtection() {
		appWideProtection(true);
		ProbeConversation a = openGroup(false);
		assertTrue("the app-wide protection must win", secure(a));
	}

	@Test
	public void aGroupBlockProtectsWhenTheAppWideProtectionIsOff() {
		appWideProtection(false);
		ProbeConversation a = openGroup(true);
		assertTrue("the group's own block must protect the window",
				secure(a));
	}

	@Test
	public void withBothOffTheWindowFollowsTheUsersChoice() {
		appWideProtection(false);
		ProbeConversation a = openGroup(false);
		assertFalse(secure(a));
	}

	@Test
	public void turningTheCreateSwitchOffKeepsTheAppWideProtection() {
		appWideProtection(true);
		GroupTrCreateActivity a = Robolectric
				.buildActivity(GroupTrCreateActivity.class).setup().get();
		MaterialSwitch sw = a.findViewById(R.id.screenshotSwitch);
		sw.setChecked(true);
		sw.setChecked(false);
		idle();
		assertTrue("the app-wide protection must win", secure(a));
	}

	@Test
	public void theCreateSwitchKeepsProtectingAcrossARestart() {
		appWideProtection(false);
		ActivityController<GroupTrCreateActivity> c = Robolectric
				.buildActivity(GroupTrCreateActivity.class).setup();
		MaterialSwitch sw = c.get().findViewById(R.id.screenshotSwitch);
		sw.setChecked(true);
		c.pause().stop().restart().start().resume();
		idle();
		assertTrue("the switch's protection must survive a restart",
				secure(c.get()));
	}
}

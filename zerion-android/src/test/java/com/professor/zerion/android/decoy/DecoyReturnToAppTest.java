package com.professor.zerion.android.decoy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.widget.FrameLayout;

import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.settings.AppIconManager;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class DecoyReturnToAppTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String SPLASH =
			"com.professor.zerion.android.splash.SplashScreenActivity";

	@After
	public void tearDown() {
		AppIconManager.setAppIcon(RuntimeEnvironment.getApplication(),
				AppIconManager.ICON_DEFAULT);
	}

	private static void assertOpensAnEnabledEntry(int icon) {
		Context ctx = RuntimeEnvironment.getApplication();
		AppIconManager.setAppIcon(ctx, icon);
		PackageManager pm = ctx.getPackageManager();

		Intent i = DecoyCalculatorActivity.realAppIntent(ctx);
		ComponentName target = i.getComponent();
		assertNotNull(target);
		assertNotEquals("icon " + icon + ": the decoy returns to a disabled "
						+ "component", PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				pm.getComponentEnabledSetting(target));
		ResolveInfo ri = pm.resolveActivity(i, 0);
		assertNotNull("icon " + icon + ": nothing resolves", ri);
		assertTrue((i.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
		assertTrue((i.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
	}

	@Test
	public void withTheCalculatorIconTheSplashIsDisabledAndTheDecoyAvoidsIt() {
		Context ctx = RuntimeEnvironment.getApplication();
		AppIconManager.setAppIcon(ctx, AppIconManager.ICON_CALCULATOR);
		assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				ctx.getPackageManager().getComponentEnabledSetting(
						new ComponentName(ctx, SPLASH)));
		assertOpensAnEnabledEntry(AppIconManager.ICON_CALCULATOR);
	}

	@Test
	public void everyIconLeadsBackIntoTheApp() {
		assertOpensAnEnabledEntry(AppIconManager.ICON_DEFAULT);
		assertOpensAnEnabledEntry(AppIconManager.ICON_CALCULATOR);
		assertOpensAnEnabledEntry(AppIconManager.ICON_NOTES);
		assertOpensAnEnabledEntry(AppIconManager.ICON_WEATHER);
	}

	@Test
	public void aStoredIconThatDisagreesWithTheLauncherStillOpensTheApp() {
		Context ctx = RuntimeEnvironment.getApplication();
		AppIconManager.setAppIcon(ctx, AppIconManager.ICON_CALCULATOR);
		AppModule.getAndroidComponent(ctx).securePreferences().edit()
				.putInt(AppIconManager.PREF_APP_ICON,
						AppIconManager.ICON_DEFAULT)
				.commit();

		Intent i = DecoyCalculatorActivity.realAppIntent(ctx);
		assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				ctx.getPackageManager().getComponentEnabledSetting(
						i.getComponent()));
		assertNotNull(ctx.getPackageManager().resolveActivity(i, 0));
	}

	@Test
	public void theCalculatorKeysStayClearOfTheSystemBars() {
		Context ctx = RuntimeEnvironment.getApplication();
		FrameLayout root = new FrameLayout(ctx);
		root.setPadding(16, 16, 16, 16);
		DecoyCalculatorActivity.keepClearOfSystemBars(root);

		WindowInsetsCompat bars = new WindowInsetsCompat.Builder()
				.setInsets(WindowInsetsCompat.Type.statusBars(),
						Insets.of(0, 48, 0, 0))
				.setInsets(WindowInsetsCompat.Type.navigationBars(),
						Insets.of(0, 0, 0, 84))
				.build();
		ViewCompat.dispatchApplyWindowInsets(root, bars);

		assertEquals(16 + 48, root.getPaddingTop());
		assertEquals(16 + 84, root.getPaddingBottom());
		assertEquals(16, root.getPaddingLeft());

		ViewCompat.dispatchApplyWindowInsets(root, bars);
		assertEquals("insets are not added twice", 16 + 84,
				root.getPaddingBottom());
		assertEquals(View.VISIBLE, root.getVisibility());
	}
}

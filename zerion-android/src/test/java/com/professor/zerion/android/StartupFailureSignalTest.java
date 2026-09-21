package com.professor.zerion.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import org.zerionproject.core.api.lifecycle.LifecycleManager.StartResult;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The startup failure signal must not be reachable from outside the app: the
 * service addresses the non-exported failure screen directly, and the
 * exported entry activity no longer reacts to a failure extra at all, so
 * another app cannot kill the process or show a fake failure screen by
 * sending an intent.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class StartupFailureSignalTest {

	@Test
	public void failureIntentTargetsTheNonExportedFailureScreen()
			throws Exception {
		Context context = RuntimeEnvironment.getApplication();
		Intent i = ZerionService.startupFailureIntent(context,
				StartResult.DB_ERROR);
		ComponentName target = i.getComponent();
		assertEquals(StartupFailureActivity.class.getName(),
				target.getClassName());
		assertEquals(StartResult.DB_ERROR.name(),
				i.getStringExtra(ZerionService.EXTRA_START_RESULT));
		assertFalse(i.hasExtra(ZerionService.EXTRA_STARTUP_FAILED));
		assertTrue((i.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
		ActivityInfo info = context.getPackageManager().getActivityInfo(
				target, PackageManager.GET_META_DATA);
		assertFalse("the failure screen must stay private to the app",
				info.exported);
	}

	@Test
	public void entryActivityHasNoFailureHandler() {
		Method found = null;
		for (Method m : com.professor.zerion.android.navdrawer
				.NavDrawerActivity.class.getDeclaredMethods()) {
			if (m.getName().equals("exitIfStartupFailed")) found = m;
		}
		assertNull("the exported entry activity must not react to a "
				+ "startup failure extra", found);
	}
}

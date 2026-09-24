package com.professor.zerion.android.security;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

/**
 * A2-AND-08: the block screen must never start the sign-in screen, which
 * re-evaluates Hardened Mode and would relaunch the block in a loop that
 * leaves the user unable to reach the password dialog.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class HardenedBlockActivityTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void theBlockScreenStartsNothingWhenSignedOut() {
		Intent i = new Intent(RuntimeEnvironment.getApplication(),
				HardenedBlockActivity.class);
		i.putExtra(HardenedBlockActivity.EXTRA_RESULT_CODE,
				SecureBootGuard.RESULT_VERIFIED_BOOT_NOT_GREEN);
		ActivityController<HardenedBlockActivity> c =
				Robolectric.buildActivity(HardenedBlockActivity.class, i);
		c.create().start().resume();
		HardenedBlockActivity a = c.get();
		assertNull("no login relaunch", shadowOf(a).getNextStartedActivity());
		assertFalse(a.isFinishing());
		c.pause().stop().destroy();
	}
}

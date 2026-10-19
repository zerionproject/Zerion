package com.professor.zerion.android.settings;

import android.app.Application;

import com.professor.zerion.android.message.PendingSends;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.vault.DirectorySyncShadow;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class ProfileSignOutClearsUnsentMessagesTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String SCOPE =
			PendingSends.groupScope(new byte[] {4, 3});

	private final Application app = ApplicationProvider.getApplicationContext();

	private static void keepSomething() {
		PendingSends.get().addNotSent(SCOPE, "secret", null, 6, true,
				() -> PendingSends.Result.failed(null));
	}

	@Test
	public void switchingProfileLeavesNothingToTellAfterwards() {
		keepSomething();
		PendingSends.get().clear();
		keepSomething();

		ProfileSignOut.signOutAndRestart(app);

		assertTrue(PendingSends.get().entries(SCOPE).isEmpty());
		assertEquals("no lock count carries into the next profile", 0,
				PendingSends.get().takeRemovedOnLock());
		assertTrue(PendingSends.get().takeNotices(SCOPE).isEmpty());
	}
}

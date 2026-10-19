package com.professor.zerion.android.grouptr;

import android.app.Application;
import android.graphics.Bitmap;
import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.account.LockManagerForTests;
import com.professor.zerion.android.account.LockManagerImpl;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.message.PendingSends;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.vault.DirectorySyncShadow;
import com.professor.zerion.android.vault.VaultManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.zerionproject.core.api.settings.SettingsManager;

import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class LockClearsUnsentMessagesTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = ApplicationProvider.getApplicationContext();
	private final byte[] group = new byte[] {7, 7};

	@Test
	public void lockingRemovesUnsentRowsAndPicturesAndSaysSoLater() {
		String scope = PendingSends.groupScope(group);
		PendingSends.get().addNotSent(scope, "secret", null, 6, true,
				() -> PendingSends.Result.failed(null));
		GroupTrMediaCache.put("x", Bitmap.createBitmap(4, 4,
				Bitmap.Config.ARGB_8888));
		assertEquals(1, GroupTrMediaCache.size());

		new LockManagerForTests().create(app, mock(SettingsManager.class),
				mock(AndroidNotificationManager.class),
				mock(VaultManager.class)).setLocked(true);

		assertTrue(PendingSends.get().entries(scope).isEmpty());
		assertEquals(0, GroupTrMediaCache.size());
		List<PendingSends.Notice> notices =
				PendingSends.get().takeNotices(scope);
		assertEquals(1, notices.size());
		assertEquals(app.getResources().getQuantityString(
				R.plurals.message_state_removed_on_lock, 1, 1),
				notices.get(0).text(app).toString());
	}

	@Test
	public void unlockingSaysOnceHowManyUnsentMessagesTheLockRemoved() {
		String scope = PendingSends.groupScope(group);
		PendingSends.get().addNotSent(scope, "secret", null, 6, true,
				() -> PendingSends.Result.failed(null));
		PendingSends.get().addNotSent(
				PendingSends.channelPostScope(new byte[] {3}), "post", null,
				4, true, () -> PendingSends.Result.failed(null));
		LockManagerImpl lock = new LockManagerForTests().create(app,
				mock(SettingsManager.class),
				mock(AndroidNotificationManager.class),
				mock(VaultManager.class));

		lock.setLocked(true);
		lock.setLocked(false);
		shadowOf(Looper.getMainLooper()).idle();

		String told = ShadowToast.getTextOfLatestToast();
		assertEquals(app.getResources().getQuantityString(
				R.plurals.message_state_removed_on_lock, 2, 2), told);
		assertFalse("only counts are told", told.contains("secret"));
		assertTrue("the screens do not say it again",
				PendingSends.get().takeNotices(scope).isEmpty());

		lock.setLocked(true);
		lock.setLocked(false);
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals("said once", 1, ShadowToast.shownToastCount());
	}
}

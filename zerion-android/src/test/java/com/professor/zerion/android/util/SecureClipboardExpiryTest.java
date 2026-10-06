package com.professor.zerion.android.util;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.DirectorySyncShadow;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.time.Duration;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class SecureClipboardExpiryTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@After
	public void tearDown() {
		SecureClipboard.clearOnSignOut(ctx);
		shadowOf(Looper.getMainLooper()).idle();
	}

	private String clip() {
		ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
		return cm.hasPrimaryClip()
				? String.valueOf(cm.getPrimaryClip().getItemAt(0).getText())
				: "";
	}

	@Test
	public void aCopyWithoutAutomaticClearSchedulesNothing() {
		shadowOf(Looper.getMainLooper()).idle();
		SecureClipboard.copySensitive(ctx, "password", "untimed-value", 0L);
		assertFalse("nothing is queued for an untimed copy",
				SecureClipboard.expiryQueued());
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofDays(2));
		assertEquals("untimed-value", clip());
		SecureClipboard.clearIfOurs(ctx);
		assertEquals("a lock or sign-out still clears it", "", clip());
	}

	@Test
	public void onlyTheLatestCopyKeepsAnExpiryQueued() {
		shadowOf(Looper.getMainLooper()).idle();
		for (int i = 0; i < 3; i++) {
			SecureClipboard.copySensitive(ctx, "password", "value-" + i,
					60_000L);
		}
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
		assertEquals("", clip());
		assertFalse("no expiry of an earlier copy is left",
				SecureClipboard.expiryQueued());
	}

	@Test
	public void aClearDropsTheQueuedExpiry() {
		shadowOf(Looper.getMainLooper()).idle();
		SecureClipboard.copySensitive(ctx, "password", "timed-value",
				600_000L);
		assertTrue(SecureClipboard.expiryQueued());
		SecureClipboard.clearIfOurs(ctx);
		assertFalse(SecureClipboard.expiryQueued());
	}
}

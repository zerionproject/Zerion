package com.professor.zerion.android.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.PersistableBundle;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.IOException;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = ClearableClipboardShadow.class)
public class UtilStaticIsolationTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long MINUTE = 60_000L;

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@Test
	public void aStagedCopyDoesNotOutliveItsTest() throws IOException {
		assertNothingStagedAndLeaveACopy();
	}

	@Test
	public void aStagedCopyDoesNotOutliveTheNextTest() throws IOException {
		assertNothingStagedAndLeaveACopy();
	}

	@Test
	public void aRememberedClipboardCopyDoesNotOutliveItsTest() {
		assertNoRememberedCopyAndLeaveOne();
	}

	@Test
	public void aRememberedClipboardCopyDoesNotOutliveTheNextTest() {
		assertNoRememberedCopyAndLeaveOne();
	}

	private void assertNothingStagedAndLeaveACopy() throws IOException {
		assertNull("a hand-off staged by an earlier test",
				ExternalHandoff.current());
		ExternalHandoff.stage(ctx, new byte[] {1, 2, 3}, "application/pdf");
		assertNotNull(ExternalHandoff.current());
	}

	private void assertNoRememberedCopyAndLeaveOne() {
		ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
		ClipData expired = ClipData.newPlainText("address", "an-old-copy");
		PersistableBundle extras = new PersistableBundle();
		extras.putBoolean(SecureClipboard.Extras.SENSITIVE, true);
		extras.putBoolean(SecureClipboard.Extras.OWNER, true);
		extras.putLong(SecureClipboard.Extras.LIFETIME, MINUTE);
		expired.getDescription().setExtras(extras);
		ReflectionHelpers.callInstanceMethod(expired.getDescription(),
				"setTimestamp", ClassParameter.from(long.class,
						System.currentTimeMillis() - 2 * MINUTE));
		cm.setPrimaryClip(expired);
		SecureClipboard.onAppFocused(ctx);
		assertFalse("a copy remembered from an earlier test kept an expired "
				+ "clip on the clipboard", cm.hasPrimaryClip());
		SecureClipboard.copy(ctx, "address", "this test's copy");
		assertTrue(cm.hasPrimaryClip());
	}
}

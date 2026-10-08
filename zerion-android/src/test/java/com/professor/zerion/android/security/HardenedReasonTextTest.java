package com.professor.zerion.android.security;

import android.content.Context;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class HardenedReasonTextTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void eachReasonComesFromTheTranslatedStrings() {
		Context ctx = RuntimeEnvironment.getApplication();
		int[][] cases = {
				{SecureBootGuard.RESULT_VERIFIED_BOOT_NOT_GREEN,
						R.string.hardened_reason_verified_boot},
				{SecureBootGuard.RESULT_BOOTLOADER_UNLOCKED,
						R.string.hardened_reason_bootloader},
				{SecureBootGuard.RESULT_ROOT_BINARY_FOUND,
						R.string.hardened_reason_root},
				{SecureBootGuard.RESULT_MAGISK_FOUND,
						R.string.hardened_reason_magisk},
				{SecureBootGuard.RESULT_DEBUGGER_ATTACHED,
						R.string.hardened_reason_debugger},
				{SecureBootGuard.RESULT_FRIDA_FOUND,
						R.string.hardened_reason_frida},
				{SecureBootGuard.RESULT_XPOSED_FOUND,
						R.string.hardened_reason_xposed},
				{SecureBootGuard.RESULT_ADB_DAEMON_LISTENING,
						R.string.hardened_reason_adb},
		};
		for (int[] c : cases) {
			assertEquals(ctx.getString(c[1]),
					SecureBootGuard.describe(c[0], ctx));
		}
	}

	@Test
	public void anUnknownResultShowsItsCode() {
		Context ctx = RuntimeEnvironment.getApplication();
		String text = SecureBootGuard.describe(
				SecureBootGuard.RESULT_SIGNATURE_MISMATCH, ctx);
		assertEquals(ctx.getString(R.string.hardened_reason_unknown,
				SecureBootGuard.RESULT_SIGNATURE_MISMATCH), text);
		assertTrue(text.contains(
				String.valueOf(SecureBootGuard.RESULT_SIGNATURE_MISMATCH)));
	}
}

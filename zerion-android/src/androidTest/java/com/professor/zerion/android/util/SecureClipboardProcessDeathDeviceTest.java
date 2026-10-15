package com.professor.zerion.android.util;

import android.content.ClipDescription;
import android.os.PersistableBundle;
import android.os.SystemClock;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SecureClipboardProcessDeathDeviceTest {

	static final String SECRET = FocusHost.TOKEN_PREFIX + "left-by-an-ended-process";
	static final long LIFETIME_MS = 4_000;

	@Test
	public void copyBeforeTheProcessEnds() {
		try (FocusHost host = FocusHost.open()) {
			host.onMain(() -> SecureClipboard.copySensitive(host.context(),
					"device-test", SECRET, LIFETIME_MS));
			assertEquals(SECRET, host.clipText());
			ClipDescription d = host.clipboard().getPrimaryClipDescription();
			assertNotNull(d);
			PersistableBundle extras = d.getExtras();
			assertNotNull(extras);
			assertEquals(LIFETIME_MS,
					extras.getLong(SecureClipboard.Extras.LIFETIME));
			assertTrue(extras.getBoolean(SecureClipboard.Extras.SENSITIVE));
			assertTrue(extras.getBoolean(SecureClipboard.Extras.OWNER));
		}
	}

	@Test
	public void clearedOnceTheAppIsFocusedAgain() {
		try (FocusHost host = FocusHost.open()) {
			try {
				long deadline = SystemClock.uptimeMillis() + 2 * LIFETIME_MS;
				String now = host.clipText();
				while (SECRET.equals(now)
						&& SystemClock.uptimeMillis() < deadline) {
					SystemClock.sleep(100);
					now = host.clipText();
				}
				assertNotEquals("the copy of the ended process is still on the"
						+ " clipboard", SECRET, now);
			} finally {
				host.clearTestClips();
			}
		}
	}
}

package com.professor.zerion.android.util;

import android.content.ClipData;
import android.content.ClipDescription;
import android.os.PersistableBundle;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SecureClipboardDeviceTest {

	private FocusHost host;

	@Before
	public void setUp() {
		host = FocusHost.open();
		host.clearTestClips();
		SecureClipboard.forgetForTests();
	}

	@After
	public void tearDown() {
		try {
			host.clearTestClips();
			SecureClipboard.forgetForTests();
		} finally {
			host.close();
		}
	}

	private static String token() {
		return FocusHost.TOKEN_PREFIX + UUID.randomUUID();
	}

	private void copy(String text, long lifetimeMs) {
		host.onMain(() -> SecureClipboard.copySensitive(host.context(),
				"device-test", text, lifetimeMs));
	}

	private String waitUntilGone(String text, long timeoutMs) {
		long deadline = SystemClock.uptimeMillis() + timeoutMs;
		String now = host.clipText();
		while (text.equals(now) && SystemClock.uptimeMillis() < deadline) {
			SystemClock.sleep(100);
			now = host.clipText();
		}
		return now;
	}

	@Test
	public void aCopiedSecretIsMarkedForTheSystem() {
		String secret = token();
		copy(secret, 5_000);
		assertEquals(secret, host.clipText());
		ClipDescription d = host.clipboard().getPrimaryClipDescription();
		assertNotNull(d);
		PersistableBundle extras = d.getExtras();
		assertNotNull(extras);
		assertTrue(extras.getBoolean(SecureClipboard.Extras.SENSITIVE));
		assertTrue(extras.getBoolean(SecureClipboard.Extras.OWNER));
		assertEquals(5_000, extras.getLong(SecureClipboard.Extras.LIFETIME));
		assertTrue("the system stamps the copy", d.getTimestamp() > 0);
	}

	@Test
	public void aSecretIsClearedWhenItsLifetimeEnds() {
		String secret = token();
		copy(secret, 1_500);
		assertEquals(secret, host.clipText());
		SystemClock.sleep(500);
		assertEquals("cleared before its lifetime", secret, host.clipText());
		assertNotEquals("the secret outlived its lifetime", secret,
				waitUntilGone(secret, 5_000));
	}

	@Test
	public void aNewerUnrelatedClipIsNeverErased() {
		String secret = token();
		String unrelated = token();
		copy(secret, 1_500);
		host.clipboard().setPrimaryClip(
				ClipData.newPlainText("unrelated", unrelated));
		SystemClock.sleep(3_000);
		assertEquals(unrelated, host.clipText());
		host.onMain(() -> {
			SecureClipboard.onAppFocused(host.context());
			SecureClipboard.clearIfOurs(host.context());
		});
		SecureClipboard.forgetForTests();
		host.onMain(() -> SecureClipboard.onAppFocused(host.context()));
		assertEquals("a clip of someone else was erased", unrelated,
				host.clipText());
	}

	@Test
	public void anExpiryInTheBackgroundNeverErasesANewerClip() {
		String secret = token();
		String unrelated = token();
		copy(secret, 1_500);
		host.leave();
		host.clipboard().setPrimaryClip(
				ClipData.newPlainText("unrelated", unrelated));
		SystemClock.sleep(3_000);
		try (FocusHost back = FocusHost.returnFromBackground()) {
			try {
				assertEquals("a newer clip was erased in the background",
						unrelated, back.clipText());
			} finally {
				back.clearTestClips();
			}
		}
	}

	@Test
	public void aCopyWhoseProcessRecordIsLostIsClearedOnFocusAfterItsLifetime() {
		String early = token();
		copy(early, 60_000);
		SecureClipboard.forgetForTests();
		host.onMain(() -> SecureClipboard.onAppFocused(host.context()));
		assertEquals("a copy still within its lifetime was cleared", early,
				host.clipText());
		String secret = token();
		copy(secret, 1_000);
		SecureClipboard.forgetForTests();
		SystemClock.sleep(1_500);
		host.onMain(() -> SecureClipboard.onAppFocused(host.context()));
		assertNotEquals("the copy outlived its lifetime without a record",
				secret, waitUntilGone(secret, 2_000));
	}
}

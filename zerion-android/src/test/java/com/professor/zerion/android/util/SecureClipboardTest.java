package com.professor.zerion.android.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * A clear removes the value this class copied and nothing else, and after a
 * copy no static field of the class holds the copied text.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecureClipboardTest {

	@Test
	public void clearRemovesOurValueAndLeavesAnotherAppsValue() {
		ClipboardManager cm = mock(ClipboardManager.class);
		Context ctx = new ContextWrapper(RuntimeEnvironment.getApplication()) {
			@Override
			public Object getSystemService(String name) {
				return Context.CLIPBOARD_SERVICE.equals(name) ? cm
						: super.getSystemService(name);
			}
		};
		when(cm.hasPrimaryClip()).thenReturn(true);
		when(cm.getPrimaryClipDescription()).thenReturn(null);

		SecureClipboard.copySensitive(ctx, "seed", "abandon ability able",
				60_000L);
		verify(cm).setPrimaryClip(any(ClipData.class));
		when(cm.getPrimaryClip()).thenReturn(
				ClipData.newPlainText("seed", "abandon ability able"));
		SecureClipboard.clearIfOurs(ctx);
		shadowOf(Looper.getMainLooper()).idle();
		verify(cm, times(1)).clearPrimaryClip();

		SecureClipboard.copySensitive(ctx, "seed", "abandon ability able",
				60_000L);
		when(cm.getPrimaryClip()).thenReturn(
				ClipData.newPlainText("other", "someone else"));
		SecureClipboard.clearIfOurs(ctx);
		shadowOf(Looper.getMainLooper()).idle();
		verify(cm, times(1)).clearPrimaryClip();
	}

	@Test
	public void noStaticFieldHoldsTheCopiedText() throws Exception {
		Context ctx = RuntimeEnvironment.getApplication();
		String secret = "correct horse battery staple";
		SecureClipboard.copySensitive(ctx, "seed", secret, 60_000L);
		boolean digestSeen = false;
		for (Field f : SecureClipboard.class.getDeclaredFields()) {
			if (!Modifier.isStatic(f.getModifiers())) continue;
			f.setAccessible(true);
			Object v = f.get(null);
			assertFalse(f.getName(), v instanceof CharSequence);
			if (v instanceof byte[]) {
				byte[] b = (byte[]) v;
				assertEquals(f.getName(), 32, b.length);
				assertFalse(f.getName(), new String(b, "ISO-8859-1")
						.contains("horse"));
				digestSeen = true;
			}
		}
		assertTrue(digestSeen);
	}
}

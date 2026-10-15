package com.professor.zerion.android.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.os.PersistableBundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecureClipboardTest {

	private final Context app = RuntimeEnvironment.getApplication();

	@Before
	public void startWithoutAnyRecord() {
		SecureClipboard.forgetForTests();
	}

	@After
	public void forgetTheRecord() {
		SecureClipboard.forgetForTests();
	}

	private static final class StampingClipboard {

		private final ClipboardManager cm = mock(ClipboardManager.class);
		private ClipData clip;
		private boolean readable = true;
		private long clock = 1_000_000L;
		private int clears;

		private StampingClipboard() {
			doAnswer(i -> {
				set(i.getArgument(0));
				return null;
			}).when(cm).setPrimaryClip(any());
			doAnswer(i -> {
				clip = null;
				clears++;
				return null;
			}).when(cm).clearPrimaryClip();
			when(cm.hasPrimaryClip()).thenAnswer(i -> readable && clip != null);
			when(cm.getPrimaryClip()).thenAnswer(i -> readable ? clip : null);
			when(cm.getPrimaryClipDescription()).thenAnswer(i ->
					readable && clip != null ? clip.getDescription() : null);
		}

		private void set(ClipData c) {
			ReflectionHelpers.callInstanceMethod(c.getDescription(),
					"setTimestamp", ClassParameter.from(long.class, ++clock));
			clip = c;
		}

		private boolean holds(String text) {
			return clip != null && clip.getItemCount() > 0
					&& text.contentEquals(clip.getItemAt(0).getText());
		}

		private Context context(Context base) {
			return new ContextWrapper(base) {
				@Override
				public Object getSystemService(String name) {
					if (Context.CLIPBOARD_SERVICE.equals(name)) return cm;
					return super.getSystemService(name);
				}
			};
		}
	}

	private static ClipData markedLikeOurs(String text) {
		ClipData clip = ClipData.newPlainText("seed", text);
		PersistableBundle extras = new PersistableBundle();
		extras.putBoolean(SecureClipboard.Extras.SENSITIVE, true);
		extras.putBoolean(SecureClipboard.Extras.OWNER, true);
		extras.putLong(SecureClipboard.Extras.LIFETIME, 60_000L);
		clip.getDescription().setExtras(extras);
		return clip;
	}

	@Test
	public void clearRemovesOurValueAndLeavesAnotherAppsValue() {
		StampingClipboard fake = new StampingClipboard();
		Context ctx = fake.context(app);
		SecureClipboard.copySensitive(ctx, "seed", "abandon ability able",
				60_000L);
		SecureClipboard.clearIfOurs(ctx);
		assertEquals(1, fake.clears);
		assertFalse(fake.holds("abandon ability able"));

		SecureClipboard.copySensitive(ctx, "seed", "abandon ability able",
				60_000L);
		fake.set(ClipData.newPlainText("other", "someone else"));
		SecureClipboard.clearIfOurs(ctx);
		assertEquals(1, fake.clears);
		assertTrue(fake.holds("someone else"));
	}

	@Test
	public void anExpiryWithoutFocusNeverErasesANewerClip() {
		StampingClipboard fake = new StampingClipboard();
		Context ctx = fake.context(app);
		SecureClipboard.copySensitive(ctx, "seed", "a copied secret", 30_000L);
		fake.readable = false;
		fake.set(ClipData.newPlainText("other", "copied in another app"));
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
		assertEquals("cleared without proof that the clip was ours", 0,
				fake.clears);
		fake.readable = true;
		SecureClipboard.onAppFocused(ctx);
		assertEquals(0, fake.clears);
		assertTrue(fake.holds("copied in another app"));
	}

	@Test
	public void anExpiryWithoutFocusLeavesOurCopyUntilTheAppIsFocused() {
		StampingClipboard fake = new StampingClipboard();
		Context ctx = fake.context(app);
		SecureClipboard.copySensitive(ctx, "seed", "a copied secret", 30_000L);
		fake.readable = false;
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
		assertTrue(fake.holds("a copied secret"));
		fake.readable = true;
		SecureClipboard.onAppFocused(ctx);
		assertFalse(fake.holds("a copied secret"));
	}

	@Test
	public void aSignOutWithoutFocusNeverErasesANewerClip() {
		StampingClipboard fake = new StampingClipboard();
		Context ctx = fake.context(app);
		SecureClipboard.copySensitive(ctx, "seed", "a copied secret", 0L);
		fake.readable = false;
		fake.set(ClipData.newPlainText("other", "copied in another app"));
		SecureClipboard.clearOnSignOut(ctx);
		fake.readable = true;
		assertTrue(fake.holds("copied in another app"));
		assertEquals(0, fake.clears);
	}

	@Test
	public void aClipMarkedLikeOursButCopiedLaterIsNotOurs() {
		StampingClipboard fake = new StampingClipboard();
		Context ctx = fake.context(app);
		SecureClipboard.copySensitive(ctx, "seed", "same text", 60_000L);
		fake.set(markedLikeOurs("same text"));
		SecureClipboard.clearIfOurs(ctx);
		assertEquals("the system timestamp did not match our copy", 0,
				fake.clears);
		assertTrue(fake.holds("same text"));
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

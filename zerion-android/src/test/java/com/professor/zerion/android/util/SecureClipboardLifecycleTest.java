package com.professor.zerion.android.util;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.os.PersistableBundle;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.DirectorySyncShadow;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.time.Duration;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class SecureClipboardLifecycleTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String SENSITIVE_EXTRA =
			"android.content.extra.IS_SENSITIVE";

	private final Context ctx = ApplicationProvider.getApplicationContext();

	private ClipboardManager clipboard() {
		return (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
	}

	@Test
	public void everyCopyIsMarkedSensitiveForTheSystem() {
		SecureClipboard.copy(ctx, "address", "a-copied-value");
		ClipDescription d = clipboard().getPrimaryClipDescription();
		assertNotNull(d);
		PersistableBundle extras = d.getExtras();
		assertNotNull("the clip carries the sensitive marker", extras);
		assertTrue(extras.getBoolean(SENSITIVE_EXTRA));
		SecureClipboard.clearIfOurs(ctx);
	}

	@Test
	public void ourClipIsCleared() {
		SecureClipboard.copy(ctx, "address", "a-copied-value");
		SecureClipboard.clearIfOurs(ctx);
		assertFalse(clipboard().hasPrimaryClip());
	}

	@Test
	public void aForeignClipIsNeverCleared() {
		SecureClipboard.copy(ctx, "address", "a-copied-value");
		clipboard().setPrimaryClip(ClipData.newPlainText("x", "foreign"));
		SecureClipboard.clearIfOurs(ctx);
		assertEquals("foreign",
				clipboard().getPrimaryClip().getItemAt(0).getText()
						.toString());
	}

	@Test
	public void aClearRequestedInTheBackgroundRunsWhenTheAppIsFocused() {
		FakeClipboard fake = new FakeClipboard();
		Context c = fake.context(ctx);
		SecureClipboard.copy(c, "address", "a-copied-value");
		fake.readable = false;
		SecureClipboard.clearIfOurs(c);
		assertTrue("ownership cannot be proved in the background",
				fake.holds("a-copied-value"));
		fake.readable = true;
		SecureClipboard.onAppFocused(c);
		assertFalse("the pending clear ran once the app was focused",
				fake.holds("a-copied-value"));
	}

	@Test
	public void aLifetimeThatEndedInTheBackgroundIsHonouredOnFocus() {
		FakeClipboard fake = new FakeClipboard();
		Context c = fake.context(ctx);
		SecureClipboard.copy(c, "address", "a-copied-value");
		fake.readable = false;
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
		assertTrue(fake.holds("a-copied-value"));
		fake.readable = true;
		SecureClipboard.onAppFocused(c);
		assertFalse("the expired copy was not cleared on focus",
				fake.holds("a-copied-value"));
	}

	@Test
	public void aForeignClipCopiedInTheBackgroundSurvivesTheFocusRetry() {
		FakeClipboard fake = new FakeClipboard();
		Context c = fake.context(ctx);
		SecureClipboard.copy(c, "address", "a-copied-value");
		fake.readable = false;
		SecureClipboard.clearIfOurs(c);
		fake.clip = ClipData.newPlainText("x", "foreign");
		fake.readable = true;
		SecureClipboard.onAppFocused(c);
		assertTrue(fake.holds("foreign"));
	}

	private static final class FakeClipboard {

		private final ClipboardManager cm = mock(ClipboardManager.class);
		private ClipData clip;
		private boolean readable = true;

		private FakeClipboard() {
			doAnswer(i -> {
				clip = i.getArgument(0);
				return null;
			}).when(cm).setPrimaryClip(any());
			doAnswer(i -> {
				clip = null;
				return null;
			}).when(cm).clearPrimaryClip();
			when(cm.hasPrimaryClip()).thenAnswer(i -> readable && clip != null);
			when(cm.getPrimaryClip()).thenAnswer(i -> readable ? clip : null);
			when(cm.getPrimaryClipDescription()).thenAnswer(i ->
					readable && clip != null ? clip.getDescription() : null);
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
}

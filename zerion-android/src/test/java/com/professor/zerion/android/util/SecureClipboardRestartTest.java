package com.professor.zerion.android.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.os.PersistableBundle;
import android.view.View;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.DirectorySyncShadow;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

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
public class SecureClipboardRestartTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long MINUTE = 60_000L;

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@Before
	public void startWithoutAnyRecord() {
		SecureClipboard.forgetForTests();
	}

	@After
	public void forgetTheRecord() {
		SecureClipboard.forgetForTests();
	}

	private static void copiedAt(ClipData clip, long when) {
		ReflectionHelpers.callInstanceMethod(clip.getDescription(),
				"setTimestamp", ClassParameter.from(long.class, when));
	}

	private static void processDied() {
		SecureClipboard.forgetForTests();
	}

	@Test
	public void ourCopyIsMarkedWithItsLifetime() {
		FakeClipboard fake = new FakeClipboard();
		SecureClipboard.copy(fake.context(ctx), "address", "value");
		PersistableBundle extras = fake.clip.getDescription().getExtras();
		assertNotNull(extras);
		assertTrue(extras.getBoolean(SecureClipboard.Extras.SENSITIVE));
		assertTrue(extras.getBoolean(SecureClipboard.Extras.OWNER));
		assertTrue(extras.getLong(SecureClipboard.Extras.LIFETIME) > 0);
		SecureClipboard.copySensitive(fake.context(ctx), "", "kept", 0);
		assertFalse("a copy the user keeps carries no lifetime",
				fake.clip.getDescription().getExtras()
						.containsKey(SecureClipboard.Extras.LIFETIME));
	}

	@Test
	public void anExpiredCopyOfAnEarlierProcessIsClearedOnFocus() {
		FakeClipboard fake = new FakeClipboard();
		Context c = fake.context(ctx);
		SecureClipboard.copy(c, "address", "a-copied-value");
		copiedAt(fake.clip, System.currentTimeMillis() - 2 * MINUTE);
		processDied();
		SecureClipboard.onAppFocused(c);
		assertFalse("the copy outlived its lifetime after a restart",
				fake.holds("a-copied-value"));
	}

	@Test
	public void aCopyOfAnEarlierProcessIsClearedWhenItsLifetimeEnds() {
		FakeClipboard fake = new FakeClipboard();
		Context c = fake.context(ctx);
		SecureClipboard.copy(c, "address", "a-copied-value");
		copiedAt(fake.clip, System.currentTimeMillis() - 20_000L);
		processDied();
		SecureClipboard.onAppFocused(c);
		assertTrue("a copy still within its lifetime stays",
				fake.holds("a-copied-value"));
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
		assertTrue(fake.holds("a-copied-value"));
		copiedAt(fake.clip, System.currentTimeMillis() - 61_000L);
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
		assertFalse("the copy was not cleared when its lifetime ended",
				fake.holds("a-copied-value"));
	}

	@Test
	public void aCopyThatCannotBeReadInTheBackgroundIsClearedOnFocus() {
		FakeClipboard fake = new FakeClipboard();
		Context c = fake.context(ctx);
		SecureClipboard.copy(c, "address", "a-copied-value");
		copiedAt(fake.clip, System.currentTimeMillis() - 2 * MINUTE);
		processDied();
		fake.readable = false;
		SecureClipboard.onAppFocused(c);
		assertTrue(fake.holds("a-copied-value"));
		fake.readable = true;
		SecureClipboard.onAppFocused(c);
		assertFalse(fake.holds("a-copied-value"));
	}

	@Test
	public void anotherAppsClipIsNeverCleared() {
		FakeClipboard fake = new FakeClipboard();
		Context c = fake.context(ctx);
		fake.clip = ClipData.newPlainText("x", "foreign");
		copiedAt(fake.clip, System.currentTimeMillis() - 10 * MINUTE);
		SecureClipboard.onAppFocused(c);
		assertTrue(fake.holds("foreign"));

		ClipData sensitive = ClipData.newPlainText("pw", "foreign secret");
		PersistableBundle extras = new PersistableBundle();
		extras.putBoolean(SecureClipboard.Extras.SENSITIVE, true);
		sensitive.getDescription().setExtras(extras);
		copiedAt(sensitive, System.currentTimeMillis() - 10 * MINUTE);
		fake.clip = sensitive;
		SecureClipboard.onAppFocused(c);
		assertTrue("a sensitive clip of another app is left alone",
				fake.holds("foreign secret"));

		ClipData timed = ClipData.newPlainText("pw", "foreign timed secret");
		PersistableBundle timedExtras = new PersistableBundle();
		timedExtras.putBoolean(SecureClipboard.Extras.SENSITIVE, true);
		timedExtras.putLong(SecureClipboard.Extras.LIFETIME, MINUTE);
		timed.getDescription().setExtras(timedExtras);
		copiedAt(timed, System.currentTimeMillis() - 10 * MINUTE);
		fake.clip = timed;
		SecureClipboard.onAppFocused(c);
		assertTrue("a clip without our marker is left alone",
				fake.holds("foreign timed secret"));
	}

	@Test
	public void aCopyKeptByTheUserIsNotClearedAfterARestart() {
		FakeClipboard fake = new FakeClipboard();
		Context c = fake.context(ctx);
		SecureClipboard.copySensitive(c, "", "kept", 0);
		copiedAt(fake.clip, System.currentTimeMillis() - 10 * MINUTE);
		processDied();
		SecureClipboard.onAppFocused(c);
		assertTrue(fake.holds("kept"));
	}

	@Test
	public void focusOnAnyActivityClearsAnExpiredCopyOfAnEarlierProcess() {
		ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
				Context.CLIPBOARD_SERVICE);
		ClipData clip = ClipData.newPlainText("address", "an-old-copy");
		PersistableBundle extras = new PersistableBundle();
		extras.putBoolean(SecureClipboard.Extras.SENSITIVE, true);
		extras.putBoolean(SecureClipboard.Extras.OWNER, true);
		extras.putLong(SecureClipboard.Extras.LIFETIME, MINUTE);
		clip.getDescription().setExtras(extras);
		copiedAt(clip, System.currentTimeMillis() - 2 * MINUTE);
		cm.setPrimaryClip(clip);

		ActivityController<Activity> controller =
				Robolectric.buildActivity(Activity.class).create();
		controller.get().setContentView(new View(controller.get()));
		controller.start().resume().visible();
		assertTrue(cm.hasPrimaryClip());
		controller.windowFocusChanged(true);
		shadowOf(Looper.getMainLooper()).idle();
		assertFalse("the expired copy survived the first focus",
				cm.hasPrimaryClip());
		controller.pause().stop().destroy();
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

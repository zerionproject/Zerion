package com.professor.zerion.android.conversation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;

import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_SCREENSHOT_PROTECTION;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VideoViewerTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class ProbePlayer extends VideoPlayerActivity {

		static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			ioExecutor = io::add;
		}
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@Before
	public void setUp() {
		MediaViewer.wiper = Runnable::run;
		ProbePlayer.io.clear();
		shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("mp4", "video/mp4");
	}

	@After
	public void tearDown() {
		MediaViewer.wiper = MediaViewer.DEFAULT_WIPER;
		uiPrefs().edit().remove(PREF_SCREENSHOT_PROTECTION).commit();
		ProbePlayer.io.clear();
		MediaViewer.clearAll();
	}

	private SharedPreferences uiPrefs() {
		ZerionApplication app = ApplicationProvider.getApplicationContext();
		return app.getApplicationComponent().uiPreferences();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static boolean secure(Activity a) {
		return (a.getWindow().getAttributes().flags
				& WindowManager.LayoutParams.FLAG_SECURE) != 0;
	}

	private int posts;
	private String post = "";

	private String shown(boolean secure) {
		Activity from = Robolectric.buildActivity(Activity.class).setup()
				.get();
		post = "channel:bb/" + (++posts);
		assertTrue(MediaViewer.playVideo(from, Runnable::run,
				new MediaViewer.Item(MediaViewerTest.mp4(), "video/mp4")
						.titled("Alice", 1000L).secure(secure)
						.from("channel:bb", post)));
		Intent started = shadowOf(from).getNextStartedActivity();
		assertEquals(VideoPlayerActivity.class.getName(),
				started.getComponent().getClassName());
		return started.getStringExtra(MediaViewer.EXTRA_TOKEN);
	}

	private ActivityController<ProbePlayer> open(String token) {
		Intent i = new Intent(ctx, ProbePlayer.class)
				.putExtra(MediaViewer.EXTRA_TOKEN, token);
		ActivityController<ProbePlayer> c =
				Robolectric.buildActivity(ProbePlayer.class, i).setup();
		idle();
		return c;
	}

	@Test
	public void thePlayerSaysWhoSentTheVideoAndWhen() {
		ProbePlayer a = open(shown(false)).get();
		assertEquals(View.VISIBLE,
				a.findViewById(R.id.video_heading).getVisibility());
		assertEquals("Alice", ((android.widget.TextView) a.findViewById(
				R.id.video_title)).getText().toString());
		android.widget.TextView date = a.findViewById(R.id.video_date);
		assertEquals(View.VISIBLE, date.getVisibility());
		assertEquals(com.professor.zerion.android.util.UiUtils
						.formatDateAbsolute(a, 1000L),
				date.getText().toString());
	}

	@Test
	public void aVideoPlaysFromMemoryAndLeavesNoCopyBehind() throws Exception {
		String token = shown(false);
		MediaViewer.Shown held = MediaViewer.get(token);
		assertNotNull(held);
		ActivityController<ProbePlayer> c = open(token);
		assertFalse(c.get().isFinishing());
		assertEquals(View.VISIBLE,
				c.get().findViewById(R.id.player_view).getVisibility());
		assertEquals(new ArrayList<>(),
				MediaViewerTest.storageHolding(ctx, MediaViewerTest.MARKER));
		c.pause().stop();
		assertNotNull("the video is gone after a short trip away",
				MediaViewer.get(token));
		c.restart().start().resume();
		c.get().finish();
		c.pause().stop().destroy();
		assertNull(MediaViewer.get(token));
		assertArrayEquals(new byte[held.data.length], held.data);
		assertEquals(new ArrayList<>(),
				MediaViewerTest.storageHolding(ctx, MediaViewerTest.MARKER));
	}

	@Test
	public void theGroupScreenshotBlockAppliesToThePlayer() {
		uiPrefs().edit().putBoolean(PREF_SCREENSHOT_PROTECTION, false)
				.commit();
		assertTrue(secure(open(shown(true)).get()));
		assertFalse(secure(open(shown(false)).get()));
	}

	@Test
	public void afterALockThePlayerCloses() {
		ActivityController<ProbePlayer> c = open(shown(false));
		c.pause().stop();
		MediaViewer.clearAll();
		idle();
		assertTrue("the player waits to be shown again before it closes",
				c.get().isFinishing());
	}

	@Test
	public void deletingThePostStopsAndClosesThePlayerAtOnce() {
		ProbePlayer a = open(shown(false)).get();
		assertFalse(a.isFinishing());
		MediaViewer.forgetPosts(java.util.Collections.singletonList(post));
		idle();
		assertTrue("the deleted video is still playing", a.isFinishing());
		assertEquals(View.GONE,
				a.findViewById(R.id.player_view).getVisibility());
	}

	@Test
	public void aChatVideoClosesWhenItsMessageIsDeleted() throws Exception {
		org.zerionproject.app.api.attachment.AttachmentHeader header =
				new org.zerionproject.app.api.attachment.AttachmentHeader(
						new org.zerionproject.core.api.sync.GroupId(
								getRandomId()),
						new org.zerionproject.core.api.sync.MessageId(
								getRandomId()), "video/mp4");
		byte[] message = getRandomId();
		Intent i = new Intent(ctx, VideoPlayerActivity.class)
				.putExtra(VideoPlayerActivity.ATTACHMENT,
						com.professor.zerion.android.attachment
								.AttachmentItemsForTests.available(header,
										"mp4"))
				.putExtra(VideoPlayerActivity.ITEM_ID, message);
		ActivityController<VideoPlayerActivity> c =
				Robolectric.buildActivity(VideoPlayerActivity.class, i)
						.create();
		VideoPlayerActivity a = c.get();
		a.dbExecutor = r -> {
		};
		c.start().resume();
		idle();
		a.eventOccurred(new org.zerionproject.app.api.autodelete.event
				.ConversationMessagesDeletedEvent(
				new org.zerionproject.core.api.contact.ContactId(1),
				java.util.Collections.singletonList(
						new org.zerionproject.core.api.sync.MessageId(
								getRandomId()))));
		idle();
		assertFalse(a.isFinishing());
		a.eventOccurred(new org.zerionproject.app.api.autodelete.event
				.ConversationMessagesDeletedEvent(
				new org.zerionproject.core.api.contact.ContactId(1),
				java.util.Collections.singletonList(
						new org.zerionproject.core.api.sync.MessageId(
								message))));
		idle();
		assertTrue("the deleted message's video is still open",
				a.isFinishing());
	}

	@Test
	public void theTopBarOffersSaveAndOpenElsewhereOnlyOnRequest() {
		try (MockedStatic<FileProvider> provider =
				mockStatic(FileProvider.class)) {
			provider.when(() -> FileProvider.getUriForFile(any(), anyString(),
					any(File.class))).thenAnswer(inv -> {
						File f = inv.getArgument(2);
						return Uri.parse("content://" + inv.getArgument(1)
								+ "/" + f.getParentFile().getName() + "/"
								+ f.getName());
					});
			ProbePlayer a = open(shown(false)).get();
			View save = a.findViewById(R.id.save_button);
			View elsewhere = a.findViewById(R.id.open_elsewhere_button);
			assertEquals(View.VISIBLE, save.getVisibility());
			assertEquals("Save video",
					String.valueOf(save.getContentDescription()));
			assertEquals("Open in another app",
					String.valueOf(elsewhere.getContentDescription()));
			float density = a.getResources().getDisplayMetrics().density;
			assertTrue(save.getLayoutParams().height >= Math.round(48 * density));
			for (Runnable r : new ArrayList<>(ProbePlayer.io)) r.run();
			idle();
			assertNull(nextView(a));
			elsewhere.performClick();
			idle();
			List<Runnable> pending = new ArrayList<>(ProbePlayer.io);
			ProbePlayer.io.clear();
			for (Runnable r : pending) r.run();
			idle();
			Intent view = nextView(a);
			assertNotNull(view);
			assertEquals("video/mp4", view.getType());
			assertEquals("external_view",
					view.getData().getPathSegments().get(0));
		}
	}

	private static Intent nextView(Activity a) {
		Intent i;
		while ((i = shadowOf(a).getNextStartedActivity()) != null) {
			if (Intent.ACTION_VIEW.equals(i.getAction())) return i;
		}
		return null;
	}
}

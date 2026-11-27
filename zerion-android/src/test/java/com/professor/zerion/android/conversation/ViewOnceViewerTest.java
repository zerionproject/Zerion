package com.professor.zerion.android.conversation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;

import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.concurrent.Executor;

import androidx.appcompat.widget.Toolbar;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_SCREENSHOT_PROTECTION;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ViewOnceViewerTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class EndlessPlayer extends VideoViewerTest.ProbePlayer {

		@Override
		void onPlaybackState(int playbackState) {
			if (playbackState != androidx.media3.common.Player.STATE_ENDED) {
				super.onPlaybackState(playbackState);
			}
		}
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private Executor savedDecoder;

	@Before
	public void setUp() {
		MediaViewer.wiper = Runnable::run;
		savedDecoder = ImageFragment.decoder;
		ImageFragment.decoder = Runnable::run;
		shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("png", "image/png");
		shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("mp4", "video/mp4");
		uiPrefs().edit().putBoolean(PREF_SCREENSHOT_PROTECTION, false)
				.commit();
	}

	@After
	public void tearDown() {
		MediaViewer.wiper = MediaViewer.DEFAULT_WIPER;
		ImageFragment.decoder = savedDecoder;
		uiPrefs().edit().remove(PREF_SCREENSHOT_PROTECTION).commit();
		ImageViewerTest.ProbeViewer.io.clear();
		VideoViewerTest.ProbePlayer.io.clear();
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

	private static String picture(boolean once) {
		Activity from = Robolectric.buildActivity(Activity.class).setup()
				.get();
		MediaViewer.Item item = new MediaViewer.Item(MediaViewerTest.jpeg(),
				"image/png").titled("Alice", 1000L).secure(false);
		if (once) item.once();
		assertTrue(MediaViewer.showImage(from, Runnable::run, item));
		return shadowOf(from).getNextStartedActivity()
				.getStringExtra(MediaViewer.EXTRA_TOKEN);
	}

	private static String video(boolean once) {
		Activity from = Robolectric.buildActivity(Activity.class).setup()
				.get();
		MediaViewer.Item item = new MediaViewer.Item(MediaViewerTest.mp4(),
				"video/mp4").titled("Alice", 1000L).secure(false);
		if (once) item.once();
		assertTrue(MediaViewer.playVideo(from, Runnable::run, item));
		return shadowOf(from).getNextStartedActivity()
				.getStringExtra(MediaViewer.EXTRA_TOKEN);
	}

	private ActivityController<ImageViewerTest.ProbeViewer> viewer(
			String token) {
		Intent i = new Intent(ctx, ImageViewerTest.ProbeViewer.class)
				.putExtra(MediaViewer.EXTRA_TOKEN, token);
		ActivityController<ImageViewerTest.ProbeViewer> c = Robolectric
				.buildActivity(ImageViewerTest.ProbeViewer.class, i).setup();
		idle();
		return c;
	}

	private ActivityController<VideoViewerTest.ProbePlayer> player(
			String token) {
		Intent i = new Intent(ctx, VideoViewerTest.ProbePlayer.class)
				.putExtra(MediaViewer.EXTRA_TOKEN, token);
		ActivityController<VideoViewerTest.ProbePlayer> c = Robolectric
				.buildActivity(VideoViewerTest.ProbePlayer.class, i).setup();
		idle();
		return c;
	}

	@Test
	public void viewOnceMediaIsAlwaysScreenshotProtected() {
		assertTrue(MediaViewer.secure(picture(true)));
		assertTrue(MediaViewer.once(picture(true)));
		assertFalse(MediaViewer.once(picture(false)));
		assertTrue("the picture can be captured",
				secure(viewer(picture(true)).get()));
		assertTrue("the video can be captured",
				secure(player(video(true)).get()));
		assertFalse(secure(viewer(picture(false)).get()));
	}

	@Test
	public void aViewOncePictureCannotBeSavedOrOpenedElsewhere() {
		ImageViewerTest.ProbeViewer a = viewer(picture(true)).get();
		Toolbar toolbar = a.findViewById(R.id.toolbar);
		assertFalse(toolbar.getMenu().findItem(R.id.action_save_image)
				.isVisible());
		assertFalse(toolbar.getMenu().findItem(R.id.action_open_elsewhere)
				.isVisible());
		while (shadowOf(a).getNextStartedActivity() != null) idle();
		shadowOf(a).clickMenuItem(R.id.action_save_image);
		shadowOf(a).clickMenuItem(R.id.action_open_elsewhere);
		idle();
		assertNull(ShadowDialog.getLatestDialog());
		assertNull(shadowOf(a).getNextStartedActivity());
		assertTrue(ImageViewerTest.ProbeViewer.io.isEmpty());

		ImageViewerTest.ProbeViewer plain = viewer(picture(false)).get();
		Toolbar plainBar = plain.findViewById(R.id.toolbar);
		assertTrue(plainBar.getMenu().findItem(R.id.action_save_image)
				.isVisible());
	}

	@Test
	public void leavingAViewOncePictureClosesItAndWipesTheCopy() {
		String token = picture(true);
		MediaViewer.Shown held = MediaViewer.get(token);
		assertNotNull(held);
		ActivityController<ImageViewerTest.ProbeViewer> c = viewer(token);
		assertFalse(c.get().isFinishing());

		c.pause().stop();

		assertTrue("the picture waits for the user to come back",
				c.get().isFinishing());
		c.destroy();
		assertNull(MediaViewer.get(token));
		assertArrayEquals(new byte[held.data.length], held.data);
	}

	@Test
	public void anOrdinaryPictureSurvivesAShortTripAway() {
		String token = picture(false);
		ActivityController<ImageViewerTest.ProbeViewer> c = viewer(token);
		c.pause().stop();
		assertFalse(c.get().isFinishing());
		assertNotNull(MediaViewer.get(token));
	}

	@Test
	public void aViewOnceVideoCannotBeSavedAndClosesWhenLeft() {
		String token = video(true);
		MediaViewer.Shown held = MediaViewer.get(token);
		assertNotNull(held);
		Intent i = new Intent(ctx, EndlessPlayer.class)
				.putExtra(MediaViewer.EXTRA_TOKEN, token);
		ActivityController<EndlessPlayer> c =
				Robolectric.buildActivity(EndlessPlayer.class, i).setup();
		idle();
		EndlessPlayer a = c.get();
		assertFalse(a.isFinishing());
		assertEquals(View.GONE, a.findViewById(R.id.save_button)
				.getVisibility());
		assertEquals(View.GONE, a.findViewById(R.id.open_elsewhere_button)
				.getVisibility());

		c.pause().stop();

		assertTrue(a.isFinishing());
		c.destroy();
		assertNull(MediaViewer.get(token));
		assertArrayEquals(new byte[held.data.length], held.data);
	}

	@Test
	public void aViewOnceVideoClosesWhenItHasPlayedToTheEnd() {
		String token = video(true);
		VideoViewerTest.ProbePlayer a = player(token).get();

		a.onPlaybackState(androidx.media3.common.Player.STATE_ENDED);
		idle();

		assertTrue("the video can be watched again", a.isFinishing());
		VideoViewerTest.ProbePlayer plain = player(video(false)).get();
		plain.onPlaybackState(androidx.media3.common.Player.STATE_ENDED);
		idle();
		assertFalse(plain.isFinishing());
	}

	@Test
	public void anOrdinaryVideoKeepsItsButtons() {
		VideoViewerTest.ProbePlayer a = player(video(false)).get();
		assertEquals(View.VISIBLE, a.findViewById(R.id.open_elsewhere_button)
				.getVisibility());
	}
}

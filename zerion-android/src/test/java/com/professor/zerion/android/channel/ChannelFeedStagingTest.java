package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.core.api.event.EventBus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelFeedStagingTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final byte[] CONTENT = "received attachment plaintext"
			.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

	public static class ProbeFeed extends ChannelFeedActivity {

		static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = mock(ChannelManager.class);
			eventBus = mock(EventBus.class);
			notificationManager = mock(AndroidNotificationManager.class);
			ioExecutor = io::add;
		}
	}

	private Context ctx;

	@Before
	public void setUp() {
		ctx = ApplicationProvider.getApplicationContext();
		ProbeFeed.io.clear();
	}

	@After
	public void tearDown() {
		ProbeFeed.io.clear();
	}

	private static void runBackgroundWork() {
		for (int round = 0; round < 4 && !ProbeFeed.io.isEmpty(); round++) {
			List<Runnable> pending = new ArrayList<>(ProbeFeed.io);
			ProbeFeed.io.clear();
			for (Runnable r : pending) {
				try {
					r.run();
				} catch (RuntimeException ignored) {
				}
			}
			shadowOf(Looper.getMainLooper()).idle();
		}
	}

	private File stage() throws IOException {
		File dir = new File(ctx.getCacheDir(), "channel_attach_view");
		dir.mkdirs();
		File f = new File(dir, "att_00112233445566.mp4");
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(CONTENT);
		}
		return f;
	}

	private ActivityController<ProbeFeed> openFeed() {
		ActivityController<ProbeFeed> c = Robolectric.buildActivity(
				ProbeFeed.class, new Intent(ctx, ProbeFeed.class)).setup();
		runBackgroundWork();
		return c;
	}

	@Test
	public void theViewerCanReadTheAttachmentWhileTheFeedIsStopped()
			throws Exception {
		ActivityController<ProbeFeed> c = openFeed();
		File staged = stage();
		c.pause().stop();
		runBackgroundWork();
		assertTrue("the staged file was removed while the viewer shows it",
				staged.exists());
		assertArrayEquals("the viewer would read overwritten bytes",
				CONTENT, Files.readAllBytes(staged.toPath()));
	}

	@Test
	public void returningToTheFeedWipesTheAttachment() throws Exception {
		ActivityController<ProbeFeed> c = openFeed();
		File staged = stage();
		c.pause().stop();
		runBackgroundWork();
		c.restart().start().resume();
		runBackgroundWork();
		assertFalse("the plaintext outlives the viewer", staged.exists());
	}

	@Test
	public void closingTheFeedWipesTheAttachment() throws Exception {
		ActivityController<ProbeFeed> c = openFeed();
		File staged = stage();
		c.pause().stop().destroy();
		runBackgroundWork();
		assertFalse("the plaintext outlives the feed", staged.exists());
	}

	@Test
	public void openingTheFeedRemovesPlaintextAnEarlierVersionLeft()
			throws Exception {
		File old = new File(new File(ctx.getNoBackupFilesDir(),
				"channel_attach_view"), "att_0123456789abcdef.pdf");
		old.getParentFile().mkdirs();
		Files.write(old.toPath(), CONTENT);
		openFeed();
		assertFalse("plaintext an earlier version staged is still there: "
				+ Arrays.toString(old.getParentFile().list()), old.exists());
	}
}

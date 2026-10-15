package com.professor.zerion.android.util;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.professor.zerion.android.grouptr.voice.GroupTrVoicePlayer;
import com.professor.zerion.android.vault.utils.PlaintextDescriptor;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class TemporaryFilesDeviceTest {

	private static final String[] SENSITIVE = {"Alice", "station", "contact-17"};
	private static final byte[] CONTENT = ("From Alice to contact-17: meet at the"
			+ " station").getBytes(StandardCharsets.UTF_8);

	private final IsolatedDirs dirs =
			new IsolatedDirs(UUID.randomUUID().toString());

	@After
	public void tearDown() {
		ExternalHandoff.end(dirs);
		dirs.remove();
	}

	private static void assertNameCarriesNoContent(File f) {
		String name = f.getName().toLowerCase(Locale.ROOT);
		for (String s : SENSITIVE) {
			assertFalse(f.getName() + " carries " + s,
					name.contains(s.toLowerCase(Locale.ROOT)));
		}
	}

	private static void waitUntilGone(File f) {
		long deadline = SystemClock.uptimeMillis() + 5_000;
		while (f.exists() && SystemClock.uptimeMillis() < deadline) {
			SystemClock.sleep(20);
		}
		assertFalse(f.getName() + " was left behind", f.exists());
	}

	private File[] files(File dir) {
		File[] files = dir.listFiles(File::isFile);
		return files == null ? new File[0] : files;
	}

	@Test
	public void aCopyHandedToAViewerIsPrivateRandomlyNamedAndRemoved()
			throws Exception {
		File staged = ExternalHandoff.stage(dirs, CONTENT, "application/pdf");
		assertTrue(staged.getName(), staged.getName().matches("[0-9a-f]{32}\\.pdf"));
		assertNameCarriesNoContent(staged);
		IsolatedDirs.assertPrivate(staged);
		IsolatedDirs.assertPrivate(staged.getParentFile());
		ExternalHandoff.end(dirs);
		waitUntilGone(staged);
	}

	@Test
	public void aViewedDocumentLeavesNoFile() throws Exception {
		File cache = dirs.getCacheDir();
		try (ParcelFileDescriptor pfd = PlaintextDescriptor.open(CONTENT, cache)) {
			assertEquals("plaintext is on disk while it is viewed", 0,
					files(cache).length);
			try (InputStream in = new ParcelFileDescriptor
					.AutoCloseInputStream(pfd.dup())) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[256];
				int n;
				while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
				assertArrayEquals(CONTENT, out.toByteArray());
			}
		}
		assertEquals(0, files(cache).length);
	}

	@Test
	public void aPlayedVoiceMessageCopyIsPrivateAndRemoved() throws Exception {
		GroupTrVoicePlayer player = new GroupTrVoicePlayer(dirs);
		CountDownLatch ended = new CountDownLatch(1);
		AtomicReference<File[]> whilePlaying = new AtomicReference<>();
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			player.play(CONTENT, new GroupTrVoicePlayer.Listener() {
				@Override
				public void onStarted() {
				}

				@Override
				public void onCompleted() {
					ended.countDown();
				}

				@Override
				public void onError() {
					ended.countDown();
				}
			});
			whilePlaying.set(files(dirs.getCacheDir()));
		});
		File[] copies = whilePlaying.get();
		assertEquals(Arrays.toString(copies), 1, copies.length);
		File copy = copies[0];
		assertTrue(copy.getName(),
				copy.getName().matches("grouptr_voice_play_\\d+\\.ogg"));
		assertNameCarriesNoContent(copy);
		ended.await(10, TimeUnit.SECONDS);
		InstrumentationRegistry.getInstrumentation().runOnMainSync(player::stop);
		waitUntilGone(copy);
	}

	@Test
	public void filesLeftByAKilledProcessAreSweptAndLiveOnesKept()
			throws Exception {
		File cache = dirs.getCacheDir();
		long start = System.currentTimeMillis();
		List<File> left = new ArrayList<>();
		for (String prefix : new String[] {"voice_attachment_", "grouptr_voice_",
				"grouptr_voice_play_", "zerion_video_", "video_thumb_", "vview",
				"jpg_meta", "vid_in_", "vid_clean_", "vid_remux_", "zbk-"}) {
			File f = File.createTempFile(prefix, ".tmp", cache);
			write(f);
			IsolatedDirs.assertPrivate(f);
			assertTrue(f.setLastModified(start - 60_000));
			left.add(f);
		}
		File live = File.createTempFile("grouptr_voice_play_", ".ogg", cache);
		write(live);
		assertTrue(live.setLastModified(start + 1_000));
		File handedOver = new File(new File(cache, ExternalHandoff.DIR),
				"0123456789abcdef0123456789abcdef.pdf");
		assertTrue(handedOver.getParentFile().mkdirs());
		write(handedOver);
		assertTrue(handedOver.setLastModified(start - 60_000));

		CacheSweeper.sweepLeftovers(dirs, start);

		for (File f : left) assertFalse(f.getName(), f.exists());
		assertTrue("a file of a live recording was swept", live.exists());
		assertTrue("a file another app may still read was swept",
				handedOver.exists());
	}

	private static void write(File f) throws IOException {
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(CONTENT);
		}
	}
}

package com.professor.zerion.android.util;

import android.os.Process;
import android.os.SystemClock;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class TemporaryFilesProcessDeathDeviceTest {

	private static final String DIRS = "process-death";
	private static final String[] PREFIXES = {"voice_attachment_",
			"grouptr_voice_", "grouptr_voice_play_", "zerion_video_", "vview",
			"jpg_meta", "vid_clean_", "zbk-"};
	private static final String[] HANDOFF_DIRS = {"vault_share",
			"zenc_share", "camera_photos", ExternalHandoff.DIR};

	@Test
	public void leaveTemporaryFilesBehind() throws Exception {
		IsolatedDirs dirs = new IsolatedDirs(DIRS);
		File cache = dirs.getCacheDir();
		for (File old : files(cache)) assertTrue(old.delete());
		for (String prefix : PREFIXES) {
			File f = File.createTempFile(prefix, ".tmp", cache);
			write(f);
			IsolatedDirs.assertPrivate(f);
		}
		assertEquals(PREFIXES.length, files(cache).length);
		for (String dir : HANDOFF_DIRS) {
			File d = new File(cache, dir);
			assertTrue(d.isDirectory() || d.mkdirs());
			for (File old : files(d)) assertTrue(old.delete());
			File f = new File(d, "handed_over.bin");
			write(f);
			IsolatedDirs.assertPrivate(f);
			assertEquals(1, files(d).length);
		}
		File camera = new File(dirs.getFilesDir(), "camera");
		assertTrue(camera.isDirectory() || camera.mkdirs());
		for (File old : files(camera)) assertTrue(old.delete());
		write(new File(camera, "temp_capture.jpg"));
	}

	@Test
	public void sweptAfterTheProcessRestarted() throws Exception {
		IsolatedDirs dirs = new IsolatedDirs(DIRS);
		try {
			File cache = dirs.getCacheDir();
			File[] left = files(cache);
			assertEquals("run leaveTemporaryFilesBehind in an earlier"
					+ " instrumentation run first", PREFIXES.length, left.length);
			long processStartedAt = System.currentTimeMillis()
					- (SystemClock.elapsedRealtime()
					- Process.getStartElapsedRealtime());
			for (String dir : HANDOFF_DIRS) {
				assertEquals(dir + " was not left behind by the earlier run",
						1, files(new File(cache, dir)).length);
			}
			File camera = new File(dirs.getFilesDir(), "camera");
			assertEquals(1, files(camera).length);
			File live = File.createTempFile("grouptr_voice_play_", ".ogg", cache);
			write(live);
			File opening = new File(new File(cache, ExternalHandoff.DIR),
					"opening_now.bin");
			write(opening);
			CacheSweeper.sweepLeftovers(dirs, processStartedAt);
			for (File f : left) {
				assertFalse(f.getName() + " of the ended process is still there",
						f.exists());
			}
			for (String dir : HANDOFF_DIRS) {
				File d = new File(cache, dir);
				assertFalse(dir + " of the ended process is still there",
						new File(d, "handed_over.bin").exists());
				assertTrue(dir + " must stay for an app that writes into it",
						d.isDirectory());
			}
			assertFalse(new File(camera, "temp_capture.jpg").exists());
			assertTrue(camera.isDirectory());
			assertTrue("a file of this process was swept", live.exists());
			assertTrue("a file of this process was swept", opening.exists());
		} finally {
			dirs.remove();
		}
	}

	private static File[] files(File dir) {
		File[] files = dir.listFiles(File::isFile);
		return files == null ? new File[0] : files;
	}

	private static void write(File f) throws IOException {
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(new byte[] {1, 2, 3});
		}
	}
}

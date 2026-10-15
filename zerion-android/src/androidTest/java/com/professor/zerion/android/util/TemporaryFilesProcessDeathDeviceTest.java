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
			File live = File.createTempFile("grouptr_voice_play_", ".ogg", cache);
			write(live);
			CacheSweeper.sweepLeftovers(dirs, processStartedAt);
			for (File f : left) {
				assertFalse(f.getName() + " of the ended process is still there",
						f.exists());
			}
			assertTrue("a file of this process was swept", live.exists());
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

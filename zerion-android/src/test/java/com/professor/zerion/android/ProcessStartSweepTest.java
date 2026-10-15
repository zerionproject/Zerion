package com.professor.zerion.android;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ProcessStartSweepTest {

	private static File write(File dir, String name, long modified)
			throws IOException {
		File f = new File(dir, name);
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(new byte[] {1, 2, 3});
		}
		assertTrue(f.setLastModified(modified));
		return f;
	}

	@Test
	public void theApplicationSweepsOnlyWhatAnEarlierProcessLeft()
			throws Exception {
		ZerionApplicationImpl app =
				(ZerionApplicationImpl) RuntimeEnvironment.getApplication();
		File cache = app.getCacheDir();
		long now = System.currentTimeMillis();
		File left = write(cache, "voice_attachment_1.m4a", now - 3_600_000L);
		File live = write(cache, "voice_attachment_2.m4a", now + 1_000L);
		app.sweepFilesOfAnEarlierProcess();
		assertFalse("a recording left by a killed process stays in the cache",
				left.exists());
		assertTrue("a recording of this process was swept", live.exists());
	}
}

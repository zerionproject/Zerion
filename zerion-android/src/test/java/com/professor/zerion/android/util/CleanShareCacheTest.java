package com.professor.zerion.android.util;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class CleanShareCacheTest {

	private static File touch(File dir, String name) throws IOException {
		dir.mkdirs();
		File f = new File(dir, name);
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(new byte[] {1, 2, 3});
		}
		return f;
	}

	@Test
	public void theShareDirectoryIsNamedOnce() {
		assertEquals("clean_share", CacheSweeper.CLEAN_SHARE_DIR);
	}

	@Test
	public void theSweepEmptiesTheShareDirectory() throws Exception {
		Context ctx = RuntimeEnvironment.getApplication();
		File dir = new File(ctx.getCacheDir(), CacheSweeper.CLEAN_SHARE_DIR);
		File shared = touch(dir, "Image 2026-11-19_101500.jpg");
		File stays = touch(ctx.getCacheDir(), "unrelated.bin");
		CacheSweeper.sweep(ctx);
		assertFalse(shared.exists());
		assertFalse(dir.exists());
		assertTrue(stays.exists());
	}

	@Test
	public void closingTheScreenShredsEveryCopyOlderThanTenMinutes()
			throws Exception {
		Context ctx = RuntimeEnvironment.getApplication();
		File dir = new File(ctx.getCacheDir(), CacheSweeper.CLEAN_SHARE_DIR);
		long now = System.currentTimeMillis();
		File first = touch(dir, "Document 2026-11-19_101500.docx");
		File second = touch(dir, "Document 2026-11-19_101500-3f9c.docx");
		File fresh = touch(dir, "Image 2026-11-19_102500.jpg");
		assertTrue(first.setLastModified(now - 11 * 60_000L));
		assertTrue(second.setLastModified(now - 11 * 60_000L));
		assertTrue(fresh.setLastModified(now - 60_000L));
		CacheSweeper.sweepDirOlderThan(ctx, CacheSweeper.CLEAN_SHARE_DIR,
				now - 10 * 60_000L);
		assertFalse("an old copy is shredded", first.exists());
		assertFalse("every old copy is shredded, not only the last",
				second.exists());
		assertTrue("a copy shared less than ten minutes ago is kept for its"
				+ " reader", fresh.exists());
	}

	@Test
	public void atStartACopyLeftByAnEarlierProcessIsSweptAndAFreshOneKept()
			throws Exception {
		Context ctx = RuntimeEnvironment.getApplication();
		File dir = new File(ctx.getCacheDir(), CacheSweeper.CLEAN_SHARE_DIR);
		long now = System.currentTimeMillis();
		long start = now - 120_000L;
		File old = touch(dir, "old.pdf");
		assertTrue(old.setLastModified(start - 60_000L));
		File fresh = touch(dir, "fresh.pdf");
		assertTrue(fresh.setLastModified(start + 60_000L));
		File ahead = touch(dir, "ahead.pdf");
		assertTrue(ahead.setLastModified(now + 3_600_000L));
		CacheSweeper.sweepLeftovers(ctx, start);
		assertFalse("a copy from before this process is swept", old.exists());
		assertFalse("a copy dated ahead of the clock is swept",
				ahead.exists());
		assertTrue("a copy this process wrote is kept for its reader",
				fresh.exists());
	}
}

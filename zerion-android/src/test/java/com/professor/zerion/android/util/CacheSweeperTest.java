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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * STO-08: every directory and prefix under which decrypted content is
 * materialised is swept, including the share, document and camera
 * directories the old sweeper missed; unrelated cache entries stay.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class CacheSweeperTest {

	private static File touch(File dir, String name) throws IOException {
		dir.mkdirs();
		File f = new File(dir, name);
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(new byte[] {1, 2, 3});
		}
		return f;
	}

	@Test
	public void sweepsEveryDecryptedTemporaryLocation() throws Exception {
		Context ctx = RuntimeEnvironment.getApplication();
		File cache = ctx.getCacheDir();
		File[] gone = {
				touch(new File(cache, "vault_share"), "report.pdf"),
				touch(new File(cache, "zenc_share"), "report.zenc"),
				touch(new File(cache, "media_docs"), "doc_1.pdf"),
				touch(new File(cache, "camera_photos"), "capture_1.jpg"),
				touch(new File(cache, "grouptr_view"), "x"),
				touch(cache, "vault_pdf_1.pdf"),
				touch(cache, "zerion_video_1.mp4"),
				touch(cache, "voice123.wav"),
				touch(new File(ctx.getFilesDir(), "camera_photos"), "old.jpg"),
		};
		File stays = touch(cache, "unrelated.bin");
		CacheSweeper.sweep(ctx);
		for (File f : gone) assertFalse(f.getPath(), f.exists());
		assertTrue(stays.exists());
	}

	/**
	 * EXT-13-F07: the vault share directory can be removed on its own, for
	 * vault lock, picker cancellation and attachment handoff, without
	 * touching the rest of the cache.
	 */
	@Test
	public void sweepsOneDirectoryOnItsOwn() throws Exception {
		Context ctx = RuntimeEnvironment.getApplication();
		File cache = ctx.getCacheDir();
		File shared = touch(new File(cache, "vault_share"), "photo.jpg");
		File other = touch(new File(cache, "grouptr_view"), "keep.bin");
		CacheSweeper.sweepDir(ctx, "vault_share");
		assertFalse(shared.exists());
		assertFalse(new File(cache, "vault_share").exists());
		assertTrue(other.exists());
	}
}

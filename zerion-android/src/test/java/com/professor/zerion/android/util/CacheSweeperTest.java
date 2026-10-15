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

	@Test
	public void sweepsTheOldChannelStagingDirectory() throws Exception {
		Context ctx = RuntimeEnvironment.getApplication();
		File noBackup = ctx.getNoBackupFilesDir();
		File old = touch(new File(noBackup, "channel_attach_view"),
				"att_0123456789abcdef.pdf");
		File keep = touch(noBackup, "unrelated.state");
		CacheSweeper.sweep(ctx);
		assertFalse("plaintext staged by an earlier version is left behind",
				old.exists());
		assertFalse(new File(noBackup, "channel_attach_view").exists());
		assertTrue(keep.exists());
	}

	private static File modifiedAt(File f, long when) {
		assertTrue(f.setLastModified(when));
		return f;
	}

	@Test
	public void atStartPlaintextLeftByAnEarlierProcessIsSwept()
			throws Exception {
		Context ctx = RuntimeEnvironment.getApplication();
		File cache = ctx.getCacheDir();
		long start = System.currentTimeMillis();
		long before = start - 60_000L;
		File[] gone = {
				modifiedAt(touch(cache, "voice_attachment_1.m4a"), before),
				modifiedAt(touch(cache, "grouptr_voice_2.ogg"), before),
				modifiedAt(touch(cache, "grouptr_voice_play_3.ogg"), before),
				modifiedAt(touch(cache, "zerion_video_4.mp4"), before),
				modifiedAt(touch(cache, "video_thumb_5.tmp"), before),
				modifiedAt(touch(cache, "grouptr_vid_thumb_6.jpg"), before),
				modifiedAt(touch(cache, "vview7.tmp"), before),
				modifiedAt(touch(cache, "vault_pdf_8.pdf"), before),
				modifiedAt(touch(cache, "jpg_meta9.jpg"), before),
				modifiedAt(touch(cache, "vid_in_10.mp4"), before),
				modifiedAt(touch(cache, "vid_clean_11.mp4"), before),
				modifiedAt(touch(cache, "vid_remux_12.webm"), before),
				modifiedAt(touch(cache, "zbk-13.tmp"), before),
				touch(new File(cache, "media_docs"), "doc_1.pdf"),
				touch(new File(cache, "grouptr_view"), "x"),
				touch(new File(cache, "channel_attach_view"), "y"),
				touch(new File(ctx.getNoBackupFilesDir(),
						"channel_attach_view"), "att_1.pdf"),
		};
		File[] stay = {
				modifiedAt(touch(cache, "voice_attachment_live.m4a"),
						start + 1000),
				modifiedAt(touch(cache, "grouptr_voice_play_live.ogg"),
						start + 1000),
				modifiedAt(touch(cache, "zerion_video_live.mp4"), start),
				modifiedAt(touch(new File(cache, ExternalHandoff.DIR),
						"open.pdf"), before),
				modifiedAt(touch(new File(cache, "camera_photos"),
						"capture.jpg"), before),
				modifiedAt(touch(new File(cache, "vault_share"), "s.pdf"),
						before),
				modifiedAt(touch(new File(cache, "zenc_share"), "s.zenc"),
						before),
				modifiedAt(touch(new File(ctx.getFilesDir(), "camera"),
						"c.jpg"), before),
				modifiedAt(touch(cache, "unrelated.bin"), before),
		};
		CacheSweeper.sweepLeftovers(ctx, start);
		for (File f : gone) assertFalse(f.getPath(), f.exists());
		for (File f : stay) assertTrue(f.getPath(), f.exists());
	}

	@Test
	public void aClaimedFileIsLeftToItsOwnerUntilReleased() throws Exception {
		Context ctx = RuntimeEnvironment.getApplication();
		File cache = ctx.getCacheDir();
		long start = System.currentTimeMillis();
		File claimed = CacheSweeper.claim(modifiedAt(touch(cache, "zbk-1.tmp"),
				start - 60_000L));
		File other = modifiedAt(touch(cache, "zbk-2.restore"), start - 60_000L);
		try {
			CacheSweeper.sweep(ctx);
			CacheSweeper.sweepLeftovers(ctx, start);
			assertTrue(claimed.exists());
			assertFalse(other.exists());
		} finally {
			CacheSweeper.release(claimed);
		}
		CacheSweeper.sweep(ctx);
		assertFalse(claimed.exists());
	}

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

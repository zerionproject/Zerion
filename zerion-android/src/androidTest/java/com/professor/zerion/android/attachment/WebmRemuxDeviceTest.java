package com.professor.zerion.android.attachment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class WebmRemuxDeviceTest {

	private static final long MAX = 50L * 1024 * 1024;

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private final List<File> temp = new ArrayList<>();

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	private File tempFile(String suffix) throws IOException {
		File f = File.createTempFile("zt_webm_", suffix, ctx.getCacheDir());
		temp.add(f);
		return f;
	}

	private SharedMediaSanitizer.Cleaned share(File in, String declared)
			throws IOException {
		byte[] raw = Files.readAllBytes(in.toPath());
		return new SharedMediaSanitizer(ctx).sanitize(Uri.fromFile(in),
				declared, raw, MAX);
	}

	private File sent(SharedMediaSanitizer.Cleaned c) throws IOException {
		File out = tempFile(".webm");
		Files.write(out.toPath(), c.getData());
		assertFalse("device details were sent", DeviceMediaFiles.contains(
				c.getData(), "com.android.".getBytes(
						StandardCharsets.US_ASCII)));
		return out;
	}

	@Test
	public void vp8WithOpusIsSentAsWebmWithEverySample() throws Exception {
		DeviceMediaFiles.Track vp8;
		DeviceMediaFiles.Track opus;
		try {
			vp8 = DeviceMediaFiles.video(MediaFormat.MIMETYPE_VIDEO_VP8, 320,
					240, 20);
			opus = DeviceMediaFiles.audio(MediaFormat.MIMETYPE_AUDIO_OPUS,
					48000, 32_000, 50);
		} catch (IOException | RuntimeException e) {
			assumeTrue("this device cannot encode VP8 and Opus: " + e, false);
			return;
		}
		File in = tempFile(".webm");
		DeviceMediaFiles.mux(in, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
				vp8, opus);
		SharedMediaSanitizer.Cleaned c = share(in, "video/webm");
		assertEquals("video/webm", c.getMimeType());
		File out = sent(c);
		assertEquals(DeviceMediaFiles.stats(in), DeviceMediaFiles.stats(out));
		MediaMetadataRetriever r = new MediaMetadataRetriever();
		try {
			r.setDataSource(out.getAbsolutePath());
			Bitmap frame = r.getFrameAtTime(0);
			assertNotNull("no frame decodes from the sent video", frame);
		} finally {
			r.release();
		}
	}

	@Test
	public void vp8WithVorbisIsSentAsWebmWithEverySample() throws Exception {
		DeviceMediaFiles.Track vp8;
		try {
			vp8 = DeviceMediaFiles.video(MediaFormat.MIMETYPE_VIDEO_VP8, 320,
					240, 20);
		} catch (IOException | RuntimeException e) {
			assumeTrue("this device cannot encode VP8: " + e, false);
			return;
		}
		File in = tempFile(".webm");
		DeviceMediaFiles.mux(in, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
				vp8, DeviceMediaFiles.syntheticVorbis(80));
		List<String> before = DeviceMediaFiles.stats(in);
		assertEquals(DeviceMediaFiles.describe(before), 2, before.size());
		SharedMediaSanitizer.Cleaned c = share(in, "application/octet-stream");
		assertEquals("video/webm", c.getMimeType());
		File out = sent(c);
		assertEquals(before, DeviceMediaFiles.stats(out));
	}
}

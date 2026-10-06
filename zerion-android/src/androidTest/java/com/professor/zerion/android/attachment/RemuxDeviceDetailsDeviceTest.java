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
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.android.vault.utils.MetadataStripper;

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
public class RemuxDeviceDetailsDeviceTest {

	private static final long MAX = 50L * 1024 * 1024;

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private final List<File> temp = new ArrayList<>();

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	private File tempFile(String suffix) throws IOException {
		File f = File.createTempFile("zt_details_", suffix, ctx.getCacheDir());
		temp.add(f);
		return f;
	}

	private static List<byte[]> deviceDetails() {
		List<byte[]> d = new ArrayList<>();
		d.add("com.android.".getBytes(StandardCharsets.US_ASCII));
		for (String s : new String[] {Build.MODEL, Build.MANUFACTURER,
				Build.DISPLAY}) {
			if (s != null && s.length() >= 5) {
				d.add(s.getBytes(StandardCharsets.UTF_8));
			}
		}
		return d;
	}

	private static void assertNoDeviceDetails(String path, byte[] out) {
		for (byte[] detail : deviceDetails()) {
			assertFalse(path + " sent " + new String(detail,
							StandardCharsets.UTF_8),
					DeviceMediaFiles.contains(out, detail));
		}
	}

	private File recordedVideo() throws IOException {
		File f = tempFile(".mp4");
		DeviceMediaFiles.mux(f, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
				DeviceMediaFiles.video(MediaFormat.MIMETYPE_VIDEO_AVC, 320,
						240, 20),
				DeviceMediaFiles.audio(MediaFormat.MIMETYPE_AUDIO_AAC, 44100,
						64_000, 40));
		byte[] raw = Files.readAllBytes(f.toPath());
		assumeTrue("this device's muxer writes no version key",
				DeviceMediaFiles.contains(raw, "com.android.version"
						.getBytes(StandardCharsets.US_ASCII)));
		return f;
	}

	private void assertPlaysLike(File in, File out) throws IOException {
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
	public void videoSharedWithAChannelOrGroupLeavesWithoutDeviceDetails()
			throws Exception {
		File in = recordedVideo();
		byte[] raw = Files.readAllBytes(in.toPath());
		for (String declared : new String[] {"video/mp4",
				"application/octet-stream"}) {
			SharedMediaSanitizer.Cleaned c = new SharedMediaSanitizer(ctx)
					.sanitize(Uri.fromFile(in), declared, raw, MAX);
			assertEquals("video/mp4", c.getMimeType());
			assertNoDeviceDetails("a shared video", c.getData());
			File out = tempFile(".mp4");
			Files.write(out.toPath(), c.getData());
			assertPlaysLike(in, out);
		}
	}

	@Test
	public void videoAttachedForAContactLeavesWithoutDeviceDetails()
			throws Exception {
		File in = recordedVideo();
		MetadataStripper.Remuxed r = new MetadataStripper(ctx)
				.remuxForSending(Uri.fromFile(in), ctx.getContentResolver(),
						true);
		temp.add(r.getFile());
		assertEquals("video/mp4", r.getMimeType());
		assertNoDeviceDetails("an attached video",
				Files.readAllBytes(r.getFile().toPath()));
		assertPlaysLike(in, r.getFile());
		File legacy = new MetadataStripper(ctx).stripVideoMetadataFromUri(
				Uri.fromFile(in), ctx.getContentResolver());
		temp.add(legacy);
		assertNoDeviceDetails("an attached video",
				Files.readAllBytes(legacy.toPath()));
	}

	@Test
	public void soundInMp4LeavesWithoutDeviceDetails() throws Exception {
		File in = tempFile(".m4a");
		DeviceMediaFiles.mux(in, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
				DeviceMediaFiles.audio(MediaFormat.MIMETYPE_AUDIO_AAC, 44100,
						64_000, 60));
		File out = new MetadataStripper(ctx).stripVideoMetadataFromUri(
				Uri.fromFile(in), ctx.getContentResolver());
		temp.add(out);
		assertNoDeviceDetails("an M4A", Files.readAllBytes(out.toPath()));
		assertEquals(DeviceMediaFiles.stats(in), DeviceMediaFiles.stats(out));
	}

	@Test
	public void videoKeptInTheVaultIsStoredWithoutDeviceDetails()
			throws Exception {
		File in = recordedVideo();
		byte[] raw = Files.readAllBytes(in.toPath());
		byte[] kept = new MetadataStripper(ctx).stripMetadata(raw,
				"video/mp4");
		assertFalse("the vault kept the video as it was",
				java.util.Arrays.equals(raw, kept));
		assertNoDeviceDetails("a vault video", kept);
		File out = tempFile(".mp4");
		Files.write(out.toPath(), kept);
		assertPlaysLike(in, out);
	}
}

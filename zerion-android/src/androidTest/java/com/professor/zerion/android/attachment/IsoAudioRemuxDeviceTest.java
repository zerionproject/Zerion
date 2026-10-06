package com.professor.zerion.android.attachment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.android.vault.utils.MetadataStripper;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class IsoAudioRemuxDeviceTest {

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private final List<File> temp = new ArrayList<>();

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	private File tempFile() throws IOException {
		File f = File.createTempFile("zt_audio_", ".m4a", ctx.getCacheDir());
		temp.add(f);
		return f;
	}

	@Test
	public void anM4aLosesItsLocationAtom() throws Exception {
		File in = tempFile();
		writeM4aWithLocation(in);
		assertNotNull("location planted", location(in));
		File out = new MetadataStripper(ctx)
				.stripVideoMetadataFromUri(Uri.fromFile(in),
						ctx.getContentResolver());
		temp.add(out);
		byte[] cleaned = Files.readAllBytes(out.toPath());
		byte[] xyz = {(byte) 0xA9, 'x', 'y', 'z'};
		assertFalse(contains(cleaned, xyz));
		assertNull(location(out));
		MediaExtractor ex = new MediaExtractor();
		try {
			ex.setDataSource(out.getAbsolutePath());
			assertEquals(1, ex.getTrackCount());
			assertTrue(ex.getTrackFormat(0).getString(MediaFormat.KEY_MIME)
					.startsWith("audio/"));
		} finally {
			ex.release();
		}
	}

	private static boolean contains(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private static String location(File f) {
		MediaMetadataRetriever r = new MediaMetadataRetriever();
		try {
			r.setDataSource(f.getAbsolutePath());
			return r.extractMetadata(
					MediaMetadataRetriever.METADATA_KEY_LOCATION);
		} finally {
			try {
				r.release();
			} catch (IOException ignored) {
			}
		}
	}

	private static void writeM4aWithLocation(File f) throws IOException {
		int rate = 44100;
		MediaFormat fmt = MediaFormat.createAudioFormat(
				MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
		fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
				MediaCodecInfo.CodecProfileLevel.AACObjectLC);
		fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64_000);
		MediaCodec enc = MediaCodec.createEncoderByType(
				MediaFormat.MIMETYPE_AUDIO_AAC);
		MediaMuxer mux = new MediaMuxer(f.getAbsolutePath(),
				MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
		mux.setLocation(52.3702f, 4.8952f);
		enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		enc.start();
		MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
		int track = -1;
		int written = 0;
		long fedFrames = 0;
		long totalFrames = rate;
		boolean inputDone = false;
		boolean outputDone = false;
		long deadline = System.currentTimeMillis() + 30_000;
		try {
			while (!outputDone && System.currentTimeMillis() < deadline) {
				if (!inputDone) {
					int idx = enc.dequeueInputBuffer(10_000);
					if (idx >= 0) {
						ByteBuffer b = enc.getInputBuffer(idx);
						b.clear();
						long pts = fedFrames * 1_000_000L / rate;
						if (fedFrames >= totalFrames) {
							enc.queueInputBuffer(idx, 0, 0, pts,
									MediaCodec.BUFFER_FLAG_END_OF_STREAM);
							inputDone = true;
						} else {
							int n = Math.min(b.remaining(), 2048) & ~1;
							b.put(new byte[n]);
							enc.queueInputBuffer(idx, 0, n, pts, 0);
							fedFrames += n / 2;
						}
					}
				}
				int out = enc.dequeueOutputBuffer(info, 10_000);
				if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					track = mux.addTrack(enc.getOutputFormat());
					mux.start();
				} else if (out >= 0) {
					ByteBuffer ob = enc.getOutputBuffer(out);
					boolean config = (info.flags
							& MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
					if (!config && info.size > 0 && track >= 0) {
						ob.position(info.offset);
						ob.limit(info.offset + info.size);
						mux.writeSampleData(track, ob, info);
						written++;
					}
					enc.releaseOutputBuffer(out, false);
					if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
							!= 0) {
						outputDone = true;
					}
				}
			}
		} finally {
			enc.stop();
			enc.release();
			if (track >= 0) mux.stop();
			mux.release();
		}
		assertTrue("encoder produced samples", written > 0);
	}
}

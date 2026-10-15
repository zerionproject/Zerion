package com.professor.zerion.android.vault.utils;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.vault.utils.SeiUserDataFilterTest.bytes;
import static com.professor.zerion.android.vault.utils.SeiUserDataFilterTest.concat;
import static com.professor.zerion.android.vault.utils.SeiUserDataFilterTest.message;
import static com.professor.zerion.android.vault.utils.SeiUserDataFilterTest.sei;
import static com.professor.zerion.android.vault.utils.SeiUserDataFilterTest.x264UserData;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RemuxUserDataTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final byte[] SC = {0, 0, 0, 1};
	private static final byte[] SPS = bytes(0x67, 0x64, 0x00, 0x1F, 0xAC,
			0xD9, 0x40, 0x50);
	private static final byte[] PPS = bytes(0x68, 0xEB, 0xE3, 0xCB);
	private static final byte[] IDR = bytes(0x65, 0x88, 0x84, 0x00, 0x33,
			0xFF, 0xFE);
	private static final byte[] SLICE = bytes(0x41, 0x9A, 0x02, 0x04, 0x13);
	private static final byte[] HEVC_IDR = bytes(0x26, 0x01, 0xAF, 0x06,
			0xB8);
	private static final byte[] HEVC_SLICE = bytes(0x02, 0x01, 0xD0, 0x09,
			0x7E);

	private Context ctx;
	private final List<byte[]> written = new ArrayList<>();
	private final List<Integer> offsets = new ArrayList<>();
	private final List<File> temp = new ArrayList<>();
	private final List<Integer> hints = new ArrayList<>();
	private int starts = 0;
	private int rotation = 0;
	private boolean webm = false;

	@Before
	public void setUp() {
		ctx = ApplicationProvider.getApplicationContext();
	}

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	private MediaFormat format(String mime) {
		MediaFormat f = mime.startsWith("video/")
				? MediaFormat.createVideoFormat(mime, 1920, 1080)
				: MediaFormat.createAudioFormat(mime, 48000, 2);
		if (rotation != 0) f.setInteger(MediaFormat.KEY_ROTATION, rotation);
		return f;
	}

	private void remux(String mime, byte[]... samples) throws IOException {
		File in = File.createTempFile("zt_sei_", ".mp4", ctx.getCacheDir());
		temp.add(in);
		Files.write(in.toPath(), new byte[64]);
		Uri uri = Uri.fromFile(in);
		int[] index = {0};
		try (MockedConstruction<MediaExtractor> ignored =
				mockConstruction(MediaExtractor.class, (m, c) -> {
					when(m.getTrackCount()).thenReturn(1);
					when(m.getTrackFormat(0)).thenReturn(format(mime));
					when(m.getSampleSize()).thenAnswer(inv ->
							index[0] < samples.length
									? (long) samples[index[0]].length : -1L);
					when(m.readSampleData(any(ByteBuffer.class), anyInt()))
							.thenAnswer(inv -> {
								if (index[0] >= samples.length) return -1;
								ByteBuffer b = inv.getArgument(0);
								byte[] s = samples[index[0]];
								b.clear();
								b.put(s);
								b.position(0);
								b.limit(s.length);
								return s.length;
							});
					when(m.advance()).thenAnswer(inv -> ++index[0]
							< samples.length);
					when(m.getSampleTime()).thenAnswer(inv ->
							index[0] * 33_333L);
				});
				MockedConstruction<MediaMuxer> ignoredToo =
						mockConstruction(MediaMuxer.class, (m, c) -> {
							when(m.addTrack(any())).thenReturn(0);
							doAnswer(inv -> {
								hints.add(inv.getArgument(0));
								return null;
							}).when(m).setOrientationHint(anyInt());
							doAnswer(inv -> {
								starts++;
								return null;
							}).when(m).start();
							doAnswer(inv -> {
								ByteBuffer b = inv.getArgument(1);
								MediaCodec.BufferInfo info = inv.getArgument(2);
								byte[] got = new byte[info.size];
								ByteBuffer view = b.duplicate();
								view.clear();
								view.position(info.offset);
								view.get(got);
								written.add(got);
								offsets.add(info.offset);
								return null;
							}).when(m).writeSampleData(anyInt(), any(), any());
							String path = (String) c.arguments().get(0);
							doAnswer(inv -> {
								Files.write(new File(path).toPath(),
										Mp4MetadataScrubberTest.box("moov",
												Mp4MetadataScrubberTest.box(
														"mvhd",
														new byte[100])));
								return null;
							}).when(m).stop();
						})) {
			File out = webm
					? new MetadataStripper(ctx).remuxForSending(uri,
							ctx.getContentResolver(), true).getFile()
					: new MetadataStripper(ctx).stripVideoMetadataFromUri(uri,
							ctx.getContentResolver());
			temp.add(out);
		}
	}

	private void assertWritten(byte[]... expected) {
		assertEquals(expected.length, written.size());
		for (int i = 0; i < expected.length; i++) {
			assertArrayEquals("sample " + i, expected[i], written.get(i));
			assertEquals("sample " + i, 0, (int) offsets.get(i));
		}
	}

	@Test
	public void aRotatedVideoKeepsItsRotation() throws Exception {
		rotation = -90;
		remux("video/avc", concat(SC, IDR));
		assertEquals(java.util.Collections.singletonList(270), hints);
	}

	@Test
	public void aRotatedVideoThatOnlyWebmCanCarryIsRefused() throws Exception {
		rotation = 90;
		webm = true;
		try {
			remux("video/x-vnd.on2.vp8", new byte[] {1, 2, 3});
			org.junit.Assert.fail("a rotated video was sent sideways as WebM");
		} catch (IOException expected) {
		}
		assertEquals("refused before anything was written", 0, starts);
	}

	@Test
	public void encoderUserDataLeavesTheRemuxedAvcSamples() throws Exception {
		byte[] userData = sei(bytes(0x06), x264UserData());
		byte[] timing = sei(bytes(0x06), message(1, bytes(0x10, 0x20)));
		remux("video/avc",
				concat(SC, SPS, SC, PPS, SC, userData, SC, timing, SC, IDR),
				concat(SC, timing, SC, SLICE),
				concat(SC, userData, SC, SLICE));
		assertWritten(concat(SC, SPS, SC, PPS, SC, timing, SC, IDR),
				concat(SC, timing, SC, SLICE), concat(SC, SLICE));
	}

	@Test
	public void encoderUserDataLeavesTheRemuxedHevcSamples()
			throws Exception {
		byte[] prefix = sei(bytes(0x4E, 0x01), x264UserData());
		byte[] suffix = sei(bytes(0x50, 0x01), x264UserData());
		byte[] hash = sei(bytes(0x50, 0x01), message(132, bytes(1, 2, 3)));
		remux("video/hevc", concat(SC, prefix, SC, HEVC_IDR, SC, suffix),
				concat(SC, HEVC_SLICE, SC, hash));
		assertWritten(concat(SC, HEVC_IDR), concat(SC, HEVC_SLICE, SC, hash));
	}

	@Test
	public void otherTracksAreCopiedAsTheyAre() throws Exception {
		byte[] lookalike = concat(SC, sei(bytes(0x06), x264UserData()), SC,
				IDR);
		remux("audio/mp4a-latm", lookalike, lookalike);
		assertWritten(lookalike, lookalike);
	}
}

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
import java.util.Arrays;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RemuxSampleBufferTest {

	static {
		TestAndroidKeyStore.register();
	}

	private Context ctx;
	private final List<byte[]> written = new ArrayList<>();
	private final List<File> temp = new ArrayList<>();

	@Before
	public void setUp() {
		ctx = ApplicationProvider.getApplicationContext();
	}

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	private static byte[] sample(int size, int index) {
		byte[] s = new byte[size];
		for (int i = 0; i < size; i++) s[i] = (byte) (i * 31 + index);
		return s;
	}

	private File remux(int[] sizes) throws IOException {
		File in = File.createTempFile("zt_remux_", ".mp4", ctx.getCacheDir());
		temp.add(in);
		Files.write(in.toPath(), new byte[64]);
		Uri uri = Uri.fromFile(in);
		int[] index = {0};
		try (MockedConstruction<MediaExtractor> ignored =
				mockConstruction(MediaExtractor.class, (m, c) -> {
					when(m.getTrackCount()).thenReturn(1);
					when(m.getTrackFormat(0)).thenReturn(
							MediaFormat.createVideoFormat("video/hevc", 3840,
									2160));
					when(m.getSampleSize()).thenAnswer(inv ->
							index[0] < sizes.length ? (long) sizes[index[0]]
									: -1L);
					when(m.readSampleData(any(ByteBuffer.class), anyInt()))
							.thenAnswer(inv -> {
								if (index[0] >= sizes.length) return -1;
								ByteBuffer b = inv.getArgument(0);
								int size = sizes[index[0]];
								if (b.capacity() < size) {
									throw new IllegalArgumentException();
								}
								b.clear();
								b.put(sample(size, index[0]));
								b.position(0);
								b.limit(size);
								return size;
							});
					when(m.advance()).thenAnswer(inv -> ++index[0]
							< sizes.length);
					when(m.getSampleTime()).thenAnswer(inv ->
							index[0] * 33_333L);
				});
				MockedConstruction<MediaMuxer> ignoredToo =
						mockConstruction(MediaMuxer.class, (m, c) -> {
							when(m.addTrack(any())).thenReturn(0);
							doAnswer(inv -> {
								ByteBuffer b = inv.getArgument(1);
								MediaCodec.BufferInfo info = inv.getArgument(2);
								byte[] got = new byte[info.size];
								ByteBuffer view = b.duplicate();
								view.position(info.offset);
								view.get(got);
								written.add(got);
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
			File out = new MetadataStripper(ctx).stripVideoMetadataFromUri(uri,
					ctx.getContentResolver());
			temp.add(out);
			return out;
		}
	}

	@Test
	public void keyFramesOf4kVideoLargerThanTheFirstBufferAreKept()
			throws Exception {
		int[] sizes = {3 * 1024 * 1024, 40 * 1024, 60 * 1024,
				5 * 1024 * 1024 + 17, 20 * 1024};
		try {
			remux(sizes);
		} catch (IOException e) {
			fail("4K video with a key frame of " + sizes[0]
					+ " bytes was refused: " + e.getCause());
		}
		assertEquals(sizes.length, written.size());
		for (int i = 0; i < sizes.length; i++) {
			assertArrayEquals("sample " + i, sample(sizes[i], i),
					written.get(i));
		}
	}

	@Test
	public void aSampleBeyondTheBoundIsRefusedAndNothingIsLeftBehind()
			throws Exception {
		int[] sizes = {40 * 1024, 64 * 1024 * 1024};
		try {
			remux(sizes);
			fail("a sample of " + sizes[1] + " bytes was accepted");
		} catch (IOException expected) {
		}
		String[] left = ctx.getCacheDir().list((d, n) ->
				n.startsWith("vid_clean_"));
		assertFalse("a partial output was left in the cache: "
				+ Arrays.toString(left), left != null && left.length > 0);
	}
}

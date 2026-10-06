package com.professor.zerion.android.vault.utils;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultImportMediaTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@Test
	public void anImageTooLargeToDecodeIsKeptWithoutACrash() {
		byte[] jpeg = new byte[4096];
		jpeg[0] = (byte) 0xFF;
		jpeg[1] = (byte) 0xD8;
		jpeg[2] = (byte) 0xFF;
		String outcome;
		try (MockedStatic<BitmapFactory> bf = mockStatic(BitmapFactory.class)) {
			bf.when(() -> BitmapFactory.decodeByteArray(any(byte[].class),
					anyInt(), anyInt(), any(BitmapFactory.Options.class)))
					.thenAnswer(inv -> {
						BitmapFactory.Options o = inv.getArgument(3);
						if (o.inJustDecodeBounds) {
							o.outWidth = 16000;
							o.outHeight = 300;
							return null;
						}
						throw new OutOfMemoryError("injected");
					});
			try {
				byte[] kept = new MetadataStripper(ctx).stripMetadata(
						jpeg.clone(), "image/jpeg");
				outcome = Arrays.equals(jpeg, kept) ? "kept as it is"
						: "changed";
			} catch (OutOfMemoryError e) {
				outcome = "crashed";
			}
		}
		assertEquals("kept as it is", outcome);
	}

	private static byte[] sample(int size, int index) {
		byte[] s = new byte[size];
		for (int i = 0; i < size; i++) s[i] = (byte) (i * 31 + index);
		return s;
	}

	@Test
	public void aVaultVideoWithLargeKeyFramesIsRemuxedInFull() {
		int[] sizes = {3 * 1024 * 1024, 40 * 1024, 5 * 1024 * 1024 + 17};
		int[] index = {0};
		List<byte[]> written = new ArrayList<>();
		byte[] original = new byte[128];
		byte[] result;
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
								MediaCodec.BufferInfo info =
										inv.getArgument(2);
								byte[] got = new byte[info.size];
								ByteBuffer view = b.duplicate();
								view.position(info.offset);
								view.get(got);
								written.add(got);
								return null;
							}).when(m).writeSampleData(anyInt(), any(),
									any());
							String path = (String) c.arguments().get(0);
							doAnswer(inv -> {
								java.nio.file.Files.write(
										new java.io.File(path).toPath(),
										Mp4MetadataScrubberTest.box("moov",
												Mp4MetadataScrubberTest.box(
														"mvhd",
														new byte[100])));
								return null;
							}).when(m).stop();
						})) {
			result = new MetadataStripper(ctx).stripMetadata(
					original.clone(), "video/mp4");
		}
		int intact = 0;
		for (int i = 0; i < written.size() && i < sizes.length; i++) {
			if (Arrays.equals(sample(sizes[i], i), written.get(i))) intact++;
		}
		assertEquals("samples remuxed 3 of 3, original with its metadata"
						+ " kept: no",
				"samples remuxed " + intact + " of " + sizes.length
						+ ", original with its metadata kept: "
						+ (Arrays.equals(original, result) ? "yes" : "no"));
	}
}

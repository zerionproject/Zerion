package com.professor.zerion.android.conversation.glide;

import android.graphics.BitmapFactory;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.data.DataFetcher;
import com.professor.zerion.android.util.MediaMagic;
import com.professor.zerion.android.util.ReceivedImageGate;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ReceivedImageGateTest {

	private static final String NO_FORMAT = "image format not accepted";
	private static final String NO_SIZE = "image dimensions not accepted";

	private static byte[] padded(byte[] head) {
		return Arrays.copyOf(head, 64);
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] box(String brand) {
		byte[] b = padded(new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p'});
		System.arraycopy(ascii(brand), 0, b, 8, 4);
		return b;
	}

	private static void chunk(ByteArrayOutputStream out, String type,
			byte[] data) {
		byte[] name = ascii(type);
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update(name, 0, 4);
		crc.update(data, 0, data.length);
		long sum = crc.getValue();
		byte[] length = {(byte) (data.length >> 24), (byte) (data.length >> 16),
				(byte) (data.length >> 8), (byte) data.length};
		byte[] check = {(byte) (sum >> 24), (byte) (sum >> 16),
				(byte) (sum >> 8), (byte) sum};
		out.write(length, 0, 4);
		out.write(name, 0, 4);
		out.write(data, 0, data.length);
		out.write(check, 0, 4);
	}

	private static byte[] png(int interlace, String... extraChunks) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A,
				0x0A};
		out.write(signature, 0, signature.length);
		chunk(out, "IHDR", new byte[] {0, 0, 3, 0x20, 0, 0, 2, 0x58, 8, 6, 0,
				0, (byte) interlace});
		for (String extra : extraChunks) {
			chunk(out, extra, ascii("second image would be here"));
		}
		chunk(out, "IDAT", new byte[] {0x78, 0x01, 1, 2, 3, 4, 5, 6, 7, 8});
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static boolean holds(byte[] data, String text) {
		return new String(data, StandardCharsets.ISO_8859_1).contains(text);
	}

	private static byte[] webp(String tag, int declaredSize) {
		byte[] w = padded(ascii("RIFF"));
		w[4] = (byte) declaredSize;
		w[5] = (byte) (declaredSize >> 8);
		System.arraycopy(ascii("WEBP" + tag), 0, w, 8, 8);
		return w;
	}

	private static byte[] jpeg(int frameMarker) {
		byte[] j = padded(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
				(byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0,
				1, 0, 0});
		if (frameMarker != 0) {
			j[20] = (byte) 0xFF;
			j[21] = (byte) frameMarker;
			j[23] = 0x11;
			j[29] = 3;
			j[39] = (byte) 0xFF;
			j[40] = (byte) 0xDA;
			j[42] = 0x0C;
			j[43] = 3;
		}
		return j;
	}

	private static byte[] jpegWithScanOf(int components) {
		byte[] j = jpeg(0xC0);
		j[43] = (byte) components;
		return j;
	}

	private static byte[] jpegWithSegment(int marker, String start) {
		byte[] base = jpeg(0xC0);
		byte[] label = ascii(start);
		byte[] out = new byte[base.length + 4 + label.length];
		System.arraycopy(base, 0, out, 0, 20);
		out[20] = (byte) 0xFF;
		out[21] = (byte) marker;
		out[22] = (byte) ((label.length + 2) >> 8);
		out[23] = (byte) (label.length + 2);
		System.arraycopy(label, 0, out, 24, label.length);
		System.arraycopy(base, 20, out, 24 + label.length, base.length - 20);
		return out;
	}

	private static byte[] gif(int frames, boolean trailer) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] head = {'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, 0, 0, 0};
		out.write(head, 0, head.length);
		byte[] frame = {0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0};
		for (int i = 0; i < frames; i++) out.write(frame, 0, frame.length);
		if (trailer) out.write(0x3B);
		return out.toByteArray();
	}

	private static final byte[] JPEG = jpeg(0xC0);
	private static final byte[] PNG = png(0);
	private static final byte[] GIF = gif(3, true);
	private static final byte[] WEBP = webp("VP8 ", 56);
	private static final byte[] HEIC = box("heic");
	private static final byte[] AVIF = box("avif");
	private static final byte[] MP4 = box("isom");
	private static final byte[] BMP = padded(new byte[] {'B', 'M', 0, 0, 0,
			0, 0, 0, 0, 0, 0x36, 0, 0, 0, 40, 0, 0, 0});
	private static final byte[] TIFF = padded(new byte[] {'I', 'I', '*', 0,
			8, 0, 0, 0, 0, 0, 0, 0});
	private static final byte[] ICO = padded(new byte[] {0, 0, 1, 0, 1, 0,
			16, 16, 0, 0, 1, 0});
	private static final byte[] WEBM = padded(new byte[] {0x1A, 0x45,
			(byte) 0xDF, (byte) 0xA3});

	private static MockedStatic<BitmapFactory> bounds(int width, int height) {
		MockedStatic<BitmapFactory> bf = mockStatic(BitmapFactory.class);
		bf.when(() -> BitmapFactory.decodeByteArray(any(byte[].class),
				anyInt(), anyInt(), any(BitmapFactory.Options.class)))
				.thenAnswer(inv -> {
					BitmapFactory.Options o = inv.getArgument(3);
					o.outWidth = width;
					o.outHeight = height;
					return null;
				});
		return bf;
	}

	private static String outcome(byte[] data, int width, int height) {
		try (MockedStatic<BitmapFactory> ignored = bounds(width, height)) {
			try {
				ReceivedImageGate.checked(data);
				return "accepted";
			} catch (IOException e) {
				return e.getMessage();
			}
		}
	}

	@Test
	public void onlyTheFourFormatsZerionSendsReachADecoder() {
		List<String> outcomes = new ArrayList<>();
		for (byte[] data : Arrays.asList(JPEG, PNG, GIF, WEBP, HEIC, AVIF, BMP,
				TIFF, ICO, MP4, new byte[4])) {
			outcomes.add(outcome(data, 800, 600));
		}
		assertEquals(Arrays.asList("accepted", "accepted", "accepted",
				"accepted", NO_FORMAT, NO_FORMAT, NO_FORMAT, NO_FORMAT,
				NO_FORMAT, NO_FORMAT, NO_FORMAT), outcomes);
	}

	@Test
	public void aContainerThatOnlyLooksLikeAnAcceptedFormatIsRefused() {
		assertEquals(NO_FORMAT, outcome(png(1), 800, 600));
		assertEquals(NO_FORMAT, outcome(webp("VP8 ", 57), 800, 600));
		assertEquals(NO_FORMAT, outcome(webp("ftyp", 56), 800, 600));
		assertEquals(NO_FORMAT, outcome(webp("VP8Z", 56), 800, 600));
		assertEquals("accepted", outcome(webp("VP8L", 56), 800, 600));
		assertEquals("accepted", outcome(webp("VP8X", 56), 800, 600));
		byte[] ftypInSize = webp("VP8 ", 56);
		System.arraycopy(ascii("ftyp"), 0, ftypInSize, 4, 4);
		assertEquals(NO_FORMAT, outcome(ftypInSize, 800, 600));
	}

	@Test
	public void aJpegMustHaveAnOrdinaryFrameAndAProgressiveOneIsKeptSmall() {
		assertEquals("accepted", outcome(jpeg(0xC1), 800, 600));
		assertEquals("accepted", outcome(jpeg(0xC2), 800, 600));
		assertEquals("accepted", outcome(jpeg(0xC2), 4000, 2000));
		assertEquals(NO_SIZE, outcome(jpeg(0xC2), 4000, 3000));
		assertEquals("accepted", outcome(jpeg(0xC0), 4000, 3000));
		assertEquals(NO_FORMAT, outcome(jpeg(0xC3), 800, 600));
		assertEquals(NO_FORMAT, outcome(jpeg(0xC9), 800, 600));
		assertEquals(NO_FORMAT, outcome(jpeg(0), 800, 600));
	}

	@Test
	public void aMarkerTheDecoderSkipsCannotHideTheRealFrame() {
		byte[] hidden = padded(new byte[] {(byte) 0xFF, (byte) 0xD8,
				(byte) 0xFF, 0x00, 0, 0x0C,
				(byte) 0xFF, (byte) 0xC2, 0, 0x11, 8, 0, 1, 0, 1, 3,
				(byte) 0xFF, (byte) 0xC0, 0, 0x11, 8, 0, 1, 0, 1, 3});
		assertEquals(NO_FORMAT, outcome(hidden, 800, 600));
		byte[] twice = jpeg(0xC0);
		twice[2] = (byte) 0xFF;
		twice[3] = (byte) 0xD8;
		assertEquals(NO_FORMAT, outcome(twice, 800, 600));
		byte[] unknown = jpeg(0xC0);
		unknown[3] = (byte) 0xF0;
		assertEquals(NO_FORMAT, outcome(unknown, 800, 600));
		assertEquals(Arrays.asList(true, true, false, false),
				Arrays.asList(ReceivedImageGate.isSequentialJpeg(jpeg(0xC0)),
						ReceivedImageGate.isSequentialJpeg(jpeg(0xC1)),
						ReceivedImageGate.isSequentialJpeg(jpeg(0xC2)),
						ReceivedImageGate.isSequentialJpeg(PNG)));
	}

	@Test
	public void aSequentialJpegInSeveralScansIsKeptAsSmallAsAProgressiveOne() {
		assertEquals("accepted", outcome(jpegWithScanOf(3), 6000, 6000));
		assertEquals(NO_SIZE, outcome(jpegWithScanOf(1), 6000, 6000));
		assertEquals("accepted", outcome(jpegWithScanOf(1), 2000, 2000));
		assertEquals(Arrays.asList(true, false),
				Arrays.asList(
						ReceivedImageGate.isSequentialJpeg(jpegWithScanOf(3)),
						ReceivedImageGate.isSequentialJpeg(jpegWithScanOf(1))));
		byte[] noScan = jpeg(0xC0);
		noScan[40] = (byte) 0xFE;
		assertEquals(NO_FORMAT, outcome(noScan, 800, 600));
		byte[] twoFrames = jpeg(0xC0);
		twoFrames[40] = (byte) 0xC0;
		assertEquals(NO_FORMAT, outcome(twoFrames, 800, 600));
	}

	@Test
	public void aSecondImageHiddenInAJpegIsNeverPointedTo() throws IOException {
		String[][] pointers = {{"E1", "http://ns.adobe.com/xap/1.0/\0<x/>"},
				{"E1", "http://ns.adobe.com/xmp/extension/\0abc"},
				{"E2", "MPF\0II*"}, {"E2", "urn:iso:std:iso:ts:21496:-1\0"},
				{"EF", "HDRGM\0"}, {"EF", "HDR_GAIN_MAP\0"}};
		try (MockedStatic<BitmapFactory> ignored = bounds(64, 64)) {
			for (String[] p : pointers) {
				int marker = Integer.parseInt(p[0], 16);
				byte[] original = jpegWithSegment(marker,
						p[1]);
				byte[] before = original.clone();
				byte[] checked = ReceivedImageGate.checked(original);
				assertArrayEquals(before, original);
				assertEquals(0xEC, checked[21] & 0xFF);
				checked[21] = (byte) marker;
				assertArrayEquals(before, checked);
			}
			byte[] exif = jpegWithSegment(0xE1, "Exif\0\0II*");
			assertEquals(0xE1, ReceivedImageGate.checked(exif)[21] & 0xFF);
			byte[] icc = jpegWithSegment(0xE2, "ICC_PROFILE\0");
			assertEquals(0xE2, ReceivedImageGate.checked(icc)[21] & 0xFF);
		}
	}

	@Test
	public void aPngIsCutDownToItsImageBeforeAnyDecoderSeesIt()
			throws IOException {
		byte[] carrier = png(0, "gdAT", "gmAP", "iCCP", "tEXt");
		try (MockedStatic<BitmapFactory> ignored = bounds(800, 600)) {
			byte[] checked = ReceivedImageGate.checked(carrier);
			assertArrayEquals(png(0), checked);
			assertFalse(holds(checked, "gdAT"));
			assertTrue(holds(carrier, "gdAT"));
			assertArrayEquals(PNG, ReceivedImageGate.checked(PNG));
		}
		assertEquals(NO_FORMAT, outcome(png(0, "CgBI"), 800, 600));
		byte[] damaged = png(0);
		damaged[damaged.length - 20] ^= 1;
		assertEquals(NO_FORMAT, outcome(damaged, 800, 600));
		byte[] noData = Arrays.copyOf(PNG, 33);
		assertEquals(NO_FORMAT, outcome(noData, 800, 600));
	}

	@Test
	public void aGifFrameMustFitInsideItsOwnPicture() {
		byte[] big = gif(2, true);
		int second = 13 + 15;
		big[second + 5] = (byte) 0x98;
		big[second + 6] = (byte) 0x3A;
		big[second + 7] = (byte) 0x98;
		big[second + 8] = (byte) 0x3A;
		assertEquals(NO_FORMAT, outcome(big, 1, 1));
		byte[] shifted = gif(2, true);
		shifted[second + 1] = 1;
		assertEquals(NO_FORMAT, outcome(shifted, 1, 1));
		byte[] empty = gif(2, true);
		empty[second + 5] = 0;
		assertEquals(NO_FORMAT, outcome(empty, 1, 1));
	}

	@Test
	public void anExtensionCannotHideFramesFromTheCount() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] start = gif(1, false);
		out.write(start, 0, start.length);
		byte[] control = {0x21, (byte) 0xF9, 4, 0, 0, 0, 0, 4};
		out.write(control, 0, control.length);
		byte[] frame = {0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0};
		for (int i = 0; i < 5; i++) out.write(frame, 0, frame.length);
		out.write(0x3B);
		out.write(0);
		out.write(0x3B);
		assertEquals(NO_FORMAT, outcome(out.toByteArray(), 1, 1));

		ByteArrayOutputStream honest = new ByteArrayOutputStream();
		honest.write(start, 0, start.length);
		byte[] loop = {0x21, (byte) 0xFF, 11, 'N', 'E', 'T', 'S', 'C', 'A',
				'P', 'E', '2', '.', '0', 3, 1, 0, 0, 0};
		honest.write(loop, 0, loop.length);
		byte[] good = {0x21, (byte) 0xF9, 4, 0, 10, 0, 0, 0};
		honest.write(good, 0, good.length);
		honest.write(frame, 0, frame.length);
		honest.write(0x3B);
		assertEquals("accepted", outcome(honest.toByteArray(), 1, 1));
	}

	@Test
	public void anAnimatedWebpIsRefused() {
		byte[] still = webp("VP8X", 56);
		byte[] animated = webp("VP8X", 56);
		animated[20] = 0x02;
		assertEquals("accepted", outcome(still, 800, 600));
		assertEquals(NO_FORMAT, outcome(animated, 800, 600));
	}

	@Test
	public void aGifMustBeWellFormedAndHaveAtMostAThousandFrames() {
		assertEquals("accepted", outcome(gif(1, true), 1, 1));
		assertEquals("accepted", outcome(
				gif(ReceivedImageGate.MAX_GIF_FRAMES, true), 1, 1));
		assertEquals(NO_FORMAT, outcome(
				gif(ReceivedImageGate.MAX_GIF_FRAMES + 1, true), 1, 1));
		assertEquals(NO_FORMAT, outcome(gif(0, true), 1, 1));
		assertEquals(NO_FORMAT, outcome(gif(3, false), 1, 1));
		byte[] junk = gif(2, true);
		junk[13] = 0x55;
		assertEquals(NO_FORMAT, outcome(junk, 1, 1));
	}

	@Test
	public void anImageWithoutSaneDimensionsOrOverTheSizeLimitIsRefused() {
		assertEquals(NO_SIZE, outcome(JPEG, 0, 0));
		assertEquals(NO_SIZE, outcome(JPEG, 16385, 10));
		assertEquals("accepted", outcome(JPEG, 16384, 2000));
		byte[] large = Arrays.copyOf(JPEG, ReceivedImageGate.MAX_BYTES + 1);
		assertEquals("image too large", outcome(large, 800, 600));
		assertNull(ReceivedImageGate.orNull(null));
		assertNull(ReceivedImageGate.orNull(HEIC));
	}

	@Test
	public void anImageWithTooManyPixelsIsRefusedWhateverItsSides() {
		assertEquals("accepted", outcome(JPEG, 7000, 5000));
		assertEquals(NO_SIZE, outcome(JPEG, 8000, 6000));
		assertEquals(NO_SIZE, outcome(PNG, 16384, 16384));
	}

	@Test
	public void theSizeLimitHoldsForAStreamThatIsOneByteTooLong() {
		byte[] atLimit = Arrays.copyOf(JPEG, ReceivedImageGate.MAX_BYTES);
		byte[] over = Arrays.copyOf(JPEG, ReceivedImageGate.MAX_BYTES + 1);
		List<String> outcomes = new ArrayList<>();
		try (MockedStatic<BitmapFactory> ignored = bounds(800, 600)) {
			for (byte[] data : Arrays.asList(atLimit, over)) {
				try {
					outcomes.add(String.valueOf(ReceivedImageGate.checked(
							new ByteArrayInputStream(data)).length));
				} catch (IOException e) {
					outcomes.add(e.getMessage());
				}
			}
		}
		assertEquals(Arrays.asList(String.valueOf(ReceivedImageGate.MAX_BYTES),
				"image too large"), outcomes);
	}

	@Test
	public void savingOrOpeningChecksAnImageOfAnySizeTheSameWay()
			throws IOException {
		byte[] large = Arrays.copyOf(JPEG, 20 * 1024 * 1024);
		try (MockedStatic<BitmapFactory> ignored = bounds(800, 600)) {
			ReceivedImageGate.checkedIfImage(large);
			assertNull(ReceivedImageGate.decode(large, 512));
		}
		try (MockedStatic<BitmapFactory> ignored = bounds(9000, 9000)) {
			ReceivedImageGate.checkedIfImage(large);
			fail("an oversized image reached the cleaner");
		} catch (IOException expected) {
			assertEquals(NO_SIZE, expected.getMessage());
		}
	}

	@Test
	public void savingOrOpeningAReceivedFileNeverDecodesAnImageTheGateRefuses()
			throws IOException {
		for (byte[] image : Arrays.asList(HEIC, AVIF, BMP, TIFF)) {
			try {
				ReceivedImageGate.checkedIfImage(image);
				fail("a refused format reached the cleaner");
			} catch (IOException expected) {
				assertEquals(NO_FORMAT, expected.getMessage());
			}
		}
		ReceivedImageGate.checkedIfImage(MP4);
		ReceivedImageGate.checkedIfImage(ascii("plain text, nothing else"));
	}

	@Test
	public void aStillImageIsNeverTreatedAsAVideo() {
		assertEquals(Arrays.asList(false, false, true, true, false),
				Arrays.asList(MediaMagic.isVideo(HEIC),
						MediaMagic.isVideo(AVIF), MediaMagic.isVideo(MP4),
						MediaMagic.isVideo(WEBM), MediaMagic.isVideo(JPEG)));
	}

	private static final class Result
			implements DataFetcher.DataCallback<InputStream> {

		@Nullable
		private byte[] data;
		@Nullable
		private Exception failure;

		@Override
		public void onDataReady(@Nullable InputStream in) {
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[256];
				int read;
				while (in != null && (read = in.read(buf)) > 0) {
					out.write(buf, 0, read);
				}
				data = out.toByteArray();
			} catch (IOException e) {
				failure = e;
			}
		}

		@Override
		public void onLoadFailed(Exception e) {
			failure = e;
		}
	}

	private static Result fetched(byte[] stored) {
		AttachmentHeader header = new AttachmentHeader(new GroupId(new byte[32]),
				new MessageId(new byte[32]), "image/jpeg");
		AttachmentReader reader = new AttachmentReader() {
			@Override
			public Attachment getAttachment(AttachmentHeader h) {
				return new Attachment(h, new ByteArrayInputStream(stored));
			}

			@Override
			public Attachment getAttachment(Transaction txn,
					AttachmentHeader h) {
				return getAttachment(h);
			}
		};
		Result result = new Result();
		try (MockedStatic<BitmapFactory> ignored = bounds(640, 480)) {
			new ZerionDataFetcher(reader, Runnable::run, header)
					.loadData(Priority.NORMAL, result);
		}
		return result;
	}

	@Test
	public void theFetcherHandsGlideOnlyBytesThatPassedTheGate() {
		Result jpeg = fetched(JPEG);
		assertArrayEquals(JPEG, jpeg.data);
		assertNull(jpeg.failure);
		for (byte[] refused : Arrays.asList(HEIC, AVIF, BMP, TIFF, MP4)) {
			Result r = fetched(refused);
			assertNull(r.data);
			assertEquals(NO_FORMAT,
					r.failure == null ? null : r.failure.getMessage());
		}
	}
}

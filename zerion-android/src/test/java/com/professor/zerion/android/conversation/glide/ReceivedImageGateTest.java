package com.professor.zerion.android.conversation.glide;

import android.graphics.Bitmap;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.data.DataFetcher;
import com.professor.zerion.android.util.MediaMagic;
import com.professor.zerion.android.util.ReceivedImageGate;
import com.professor.zerion.android.util.SafeImageDecoder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ReceivedImageGateTest {

	private static final String NO_FORMAT = "image format not accepted";
	private static final String NO_SIZE = "image dimensions not accepted";

	@After
	public void restoreTheDecoder() {
		ReceivedImageGate.useDecoder(SafeImageDecoder::decode);
	}

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

	private static byte[] jpeg(int frameMarker, int width, int height) {
		byte[] j = padded(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
				(byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0,
				1, 0, 0});
		if (frameMarker != 0) {
			j[20] = (byte) 0xFF;
			j[21] = (byte) frameMarker;
			j[23] = 0x11;
			j[24] = 8;
			j[25] = (byte) (height >> 8);
			j[26] = (byte) height;
			j[27] = (byte) (width >> 8);
			j[28] = (byte) width;
			j[29] = 3;
			j[39] = (byte) 0xFF;
			j[40] = (byte) 0xDA;
			j[42] = 0x0C;
			j[43] = 3;
		}
		return j;
	}

	private static byte[] jpeg(int frameMarker) {
		return jpeg(frameMarker, 800, 600);
	}

	private static byte[] jpegWithScanOf(int components, int side) {
		byte[] j = jpeg(0xC0, side, side);
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

	private static void chunk(ByteArrayOutputStream out, String type,
			byte[] data) {
		byte[] name = ascii(type);
		CRC32 crc = new CRC32();
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

	private static byte[] png(int width, int height, int interlace,
			String... extraChunks) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A,
				0x0A};
		out.write(signature, 0, signature.length);
		chunk(out, "IHDR", new byte[] {(byte) (width >> 24),
				(byte) (width >> 16), (byte) (width >> 8), (byte) width,
				(byte) (height >> 24), (byte) (height >> 16),
				(byte) (height >> 8), (byte) height, 8, 6, 0, 0,
				(byte) interlace});
		for (String extra : extraChunks) {
			chunk(out, extra, ascii("second image would be here"));
		}
		chunk(out, "IDAT", new byte[] {0x78, 0x01, 1, 2, 3, 4, 5, 6, 7, 8});
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static byte[] png(int interlace, String... extraChunks) {
		return png(800, 600, interlace, extraChunks);
	}

	private static byte[] gif(int width, int height, int frames,
			boolean trailer) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] head = {'G', 'I', 'F', '8', '9', 'a', (byte) width,
				(byte) (width >> 8), (byte) height, (byte) (height >> 8), 0,
				0, 0};
		out.write(head, 0, head.length);
		byte[] frame = {0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0};
		for (int i = 0; i < frames; i++) out.write(frame, 0, frame.length);
		if (trailer) out.write(0x3B);
		return out.toByteArray();
	}

	private static byte[] gif(int frames, boolean trailer) {
		return gif(1, 1, frames, trailer);
	}

	private static byte[] webp(String tag, int declaredSize) {
		byte[] w = padded(ascii("RIFF"));
		w[4] = (byte) declaredSize;
		w[5] = (byte) (declaredSize >> 8);
		System.arraycopy(ascii("WEBP" + tag), 0, w, 8, 8);
		return w;
	}

	private static byte[] webpLossy(int width, int height) {
		byte[] w = webp("VP8 ", 56);
		w[23] = (byte) 0x9D;
		w[24] = 0x01;
		w[25] = 0x2A;
		w[26] = (byte) width;
		w[27] = (byte) (width >> 8);
		w[28] = (byte) height;
		w[29] = (byte) (height >> 8);
		return w;
	}

	private static byte[] webpLossless(int width, int height) {
		byte[] w = webp("VP8L", 56);
		w[20] = 0x2F;
		int bits = (width - 1) | (height - 1) << 14;
		w[21] = (byte) bits;
		w[22] = (byte) (bits >> 8);
		w[23] = (byte) (bits >> 16);
		w[24] = (byte) (bits >> 24);
		return w;
	}

	private static byte[] webpExtended(int width, int height, int flags) {
		byte[] w = webp("VP8X", 56);
		w[20] = (byte) flags;
		w[24] = (byte) (width - 1);
		w[25] = (byte) ((width - 1) >> 8);
		w[26] = (byte) ((width - 1) >> 16);
		w[27] = (byte) (height - 1);
		w[28] = (byte) ((height - 1) >> 8);
		w[29] = (byte) ((height - 1) >> 16);
		return w;
	}

	private static boolean holds(byte[] data, String text) {
		return new String(data, StandardCharsets.ISO_8859_1).contains(text);
	}

	private static final byte[] JPEG = jpeg(0xC0);
	private static final byte[] PNG = png(0);
	private static final byte[] GIF = gif(3, true);
	private static final byte[] WEBP = webpLossy(800, 600);
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

	private static String outcome(byte[] data) {
		try {
			ReceivedImageGate.checked(data);
			return "accepted";
		} catch (IOException e) {
			return e.getMessage();
		}
	}

	private static String size(byte[] data) {
		long size = ReceivedImageGate.dimensions(data);
		return size == -1 ? "none" : (size >> 32) + "x" + (int) size;
	}

	@Test
	public void onlyTheFourFormatsZerionSendsReachADecoder() {
		List<String> outcomes = new ArrayList<>();
		for (byte[] data : Arrays.asList(JPEG, PNG, GIF, WEBP, HEIC, AVIF, BMP,
				TIFF, ICO, MP4, new byte[4])) {
			outcomes.add(outcome(data));
		}
		assertEquals(Arrays.asList("accepted", "accepted", "accepted",
				"accepted", NO_FORMAT, NO_FORMAT, NO_FORMAT, NO_FORMAT,
				NO_FORMAT, NO_FORMAT, NO_FORMAT), outcomes);
	}

	@Test
	public void theSizeIsReadFromTheFileWithoutADecoder() {
		assertEquals("800x600", size(JPEG));
		assertEquals("800x600", size(PNG));
		assertEquals("320x200", size(gif(320, 200, 1, true)));
		assertEquals("800x600", size(WEBP));
		assertEquals("4000x3000", size(webpLossless(4000, 3000)));
		assertEquals("16384x9000", size(webpExtended(16384, 9000, 0)));
		assertEquals("none", size(webpExtended(16385, 10, 0)));
		assertEquals("none", size(webp("VP8 ", 56)));
		assertEquals("none", size(jpeg(0xC0, 0, 600)));
		assertEquals("none", size(png(20000, 10, 0)));
		assertEquals("none", size(HEIC));
		assertEquals("image/jpeg", ReceivedImageGate.mimeType(JPEG));
		assertEquals("image/png", ReceivedImageGate.mimeType(PNG));
		assertEquals("image/gif", ReceivedImageGate.mimeType(GIF));
		assertEquals("image/webp", ReceivedImageGate.mimeType(WEBP));
	}

	@Test
	public void aContainerThatOnlyLooksLikeAnAcceptedFormatIsRefused() {
		assertEquals(NO_FORMAT, outcome(png(1)));
		assertEquals(NO_FORMAT, outcome(webp("VP8 ", 57)));
		assertEquals(NO_FORMAT, outcome(webp("ftyp", 56)));
		assertEquals(NO_FORMAT, outcome(webp("VP8Z", 56)));
		assertEquals("accepted", outcome(webpLossless(800, 600)));
		assertEquals("accepted", outcome(webpExtended(800, 600, 0)));
		byte[] ftypInSize = webpLossy(800, 600);
		System.arraycopy(ascii("ftyp"), 0, ftypInSize, 4, 4);
		assertEquals(NO_FORMAT, outcome(ftypInSize));
	}

	@Test
	public void aJpegMustHaveAnOrdinaryFrameAndAProgressiveOneIsKeptSmall() {
		assertEquals("accepted", outcome(jpeg(0xC1)));
		assertEquals("accepted", outcome(jpeg(0xC2)));
		assertEquals("accepted", outcome(jpeg(0xC2, 4000, 2000)));
		assertEquals(NO_SIZE, outcome(jpeg(0xC2, 4000, 3000)));
		assertEquals("accepted", outcome(jpeg(0xC0, 4000, 3000)));
		assertEquals(NO_FORMAT, outcome(jpeg(0xC3)));
		assertEquals(NO_FORMAT, outcome(jpeg(0xC9)));
		assertEquals(NO_FORMAT, outcome(jpeg(0)));
	}

	@Test
	public void aSequentialJpegInSeveralScansIsKeptAsSmallAsAProgressiveOne() {
		assertEquals("accepted", outcome(jpegWithScanOf(3, 6000)));
		assertEquals(NO_SIZE, outcome(jpegWithScanOf(1, 6000)));
		assertEquals("accepted", outcome(jpegWithScanOf(1, 2000)));
		assertEquals(Arrays.asList(true, false), Arrays.asList(
				ReceivedImageGate.isSequentialJpeg(jpegWithScanOf(3, 600)),
				ReceivedImageGate.isSequentialJpeg(jpegWithScanOf(1, 600))));
		byte[] noScan = jpeg(0xC0);
		noScan[40] = (byte) 0xFE;
		assertEquals(NO_FORMAT, outcome(noScan));
		byte[] twoFrames = jpeg(0xC0);
		twoFrames[40] = (byte) 0xC0;
		assertEquals(NO_FORMAT, outcome(twoFrames));
	}

	@Test
	public void aMarkerTheDecoderSkipsCannotHideTheRealFrame() {
		byte[] hidden = padded(new byte[] {(byte) 0xFF, (byte) 0xD8,
				(byte) 0xFF, 0x00, 0, 0x0C,
				(byte) 0xFF, (byte) 0xC2, 0, 0x11, 8, 0, 1, 0, 1, 3,
				(byte) 0xFF, (byte) 0xC0, 0, 0x11, 8, 0, 1, 0, 1, 3});
		assertEquals(NO_FORMAT, outcome(hidden));
		byte[] twice = jpeg(0xC0);
		twice[2] = (byte) 0xFF;
		twice[3] = (byte) 0xD8;
		assertEquals(NO_FORMAT, outcome(twice));
		byte[] unknown = jpeg(0xC0);
		unknown[3] = (byte) 0xF0;
		assertEquals(NO_FORMAT, outcome(unknown));
		assertEquals(Arrays.asList(true, true, false, false),
				Arrays.asList(ReceivedImageGate.isSequentialJpeg(jpeg(0xC0)),
						ReceivedImageGate.isSequentialJpeg(jpeg(0xC1)),
						ReceivedImageGate.isSequentialJpeg(jpeg(0xC2)),
						ReceivedImageGate.isSequentialJpeg(PNG)));
	}

	@Test
	public void aSecondImageHiddenInAJpegIsNeverPointedTo() throws IOException {
		String[][] pointers = {{"E1", "http://ns.adobe.com/xap/1.0/\0<x/>"},
				{"E1", "http://ns.adobe.com/xmp/extension/\0abc"},
				{"E2", "MPF\0II*"}, {"E2", "urn:iso:std:iso:ts:21496:-1\0"},
				{"EF", "HDRGM\0"}, {"EF", "HDR_GAIN_MAP\0"}};
		for (String[] p : pointers) {
			int marker = Integer.parseInt(p[0], 16);
			byte[] original = jpegWithSegment(marker, p[1]);
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

	@Test
	public void aPngIsCutDownToItsImageBeforeAnyDecoderSeesIt()
			throws IOException {
		byte[] carrier = png(0, "gdAT", "gmAP", "iCCP", "tEXt");
		byte[] checked = ReceivedImageGate.checked(carrier);
		assertArrayEquals(png(0), checked);
		assertFalse(holds(checked, "gdAT"));
		assertTrue(holds(carrier, "gdAT"));
		assertArrayEquals(PNG, ReceivedImageGate.checked(PNG));
		assertEquals(NO_FORMAT, outcome(png(0, "CgBI")));
		byte[] damaged = png(0);
		damaged[damaged.length - 20] ^= 1;
		assertEquals(NO_FORMAT, outcome(damaged));
		byte[] noData = Arrays.copyOf(PNG, 33);
		assertEquals(NO_FORMAT, outcome(noData));
	}

	@Test
	public void aGifFrameMustFitInsideItsOwnPicture() {
		byte[] big = gif(2, true);
		int second = 13 + 15;
		big[second + 5] = (byte) 0x98;
		big[second + 6] = (byte) 0x3A;
		big[second + 7] = (byte) 0x98;
		big[second + 8] = (byte) 0x3A;
		assertEquals(NO_FORMAT, outcome(big));
		byte[] shifted = gif(2, true);
		shifted[second + 1] = 1;
		assertEquals(NO_FORMAT, outcome(shifted));
		byte[] empty = gif(2, true);
		empty[second + 5] = 0;
		assertEquals(NO_FORMAT, outcome(empty));
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
		assertEquals(NO_FORMAT, outcome(out.toByteArray()));

		ByteArrayOutputStream honest = new ByteArrayOutputStream();
		honest.write(start, 0, start.length);
		byte[] loop = {0x21, (byte) 0xFF, 11, 'N', 'E', 'T', 'S', 'C', 'A',
				'P', 'E', '2', '.', '0', 3, 1, 0, 0, 0};
		honest.write(loop, 0, loop.length);
		byte[] good = {0x21, (byte) 0xF9, 4, 0, 10, 0, 0, 0};
		honest.write(good, 0, good.length);
		honest.write(frame, 0, frame.length);
		honest.write(0x3B);
		assertEquals("accepted", outcome(honest.toByteArray()));
	}

	@Test
	public void anAnimatedWebpIsRefused() {
		assertEquals("accepted", outcome(webpExtended(800, 600, 0)));
		assertEquals(NO_FORMAT, outcome(webpExtended(800, 600, 0x02)));
	}

	@Test
	public void aGifMustBeWellFormedAndHaveAtMostAThousandFrames() {
		assertEquals("accepted", outcome(gif(1, true)));
		assertEquals("accepted",
				outcome(gif(ReceivedImageGate.MAX_GIF_FRAMES, true)));
		assertEquals(NO_FORMAT,
				outcome(gif(ReceivedImageGate.MAX_GIF_FRAMES + 1, true)));
		assertEquals(NO_FORMAT, outcome(gif(0, true)));
		assertEquals(NO_FORMAT, outcome(gif(3, false)));
		byte[] junk = gif(2, true);
		junk[13] = 0x55;
		assertEquals(NO_FORMAT, outcome(junk));
	}

	@Test
	public void anImageWithoutSaneDimensionsOrOverTheSizeLimitIsRefused() {
		assertEquals(NO_SIZE, outcome(jpeg(0xC0, 0, 0)));
		assertEquals(NO_SIZE, outcome(jpeg(0xC0, 16385, 10)));
		assertEquals("accepted", outcome(jpeg(0xC0, 16384, 2000)));
		byte[] large = Arrays.copyOf(JPEG, ReceivedImageGate.MAX_BYTES + 1);
		assertEquals("image too large", outcome(large));
		assertNull(ReceivedImageGate.orNull(null));
		assertNull(ReceivedImageGate.orNull(HEIC));
	}

	@Test
	public void anImageWithTooManyPixelsIsRefusedWhateverItsFormat() {
		assertEquals("accepted", outcome(jpeg(0xC0, 7000, 5000)));
		assertEquals(NO_SIZE, outcome(jpeg(0xC0, 8000, 6000)));
		assertEquals(NO_SIZE, outcome(png(16384, 16384, 0)));
		assertEquals(NO_SIZE, outcome(gif(8000, 6000, 1, true)));
		assertEquals(NO_SIZE, outcome(webpLossless(8000, 6000)));
		assertEquals(NO_SIZE, outcome(webpExtended(8000, 6000, 0)));
		assertEquals("accepted", outcome(webpLossy(7000, 5000)));
	}

	@Test
	public void theSizeLimitHoldsForAStreamThatIsOneByteTooLong() {
		byte[] atLimit = Arrays.copyOf(JPEG, ReceivedImageGate.MAX_BYTES);
		byte[] over = Arrays.copyOf(JPEG, ReceivedImageGate.MAX_BYTES + 1);
		List<String> outcomes = new ArrayList<>();
		for (byte[] data : Arrays.asList(atLimit, over)) {
			try {
				outcomes.add(String.valueOf(ReceivedImageGate.checked(
						new ByteArrayInputStream(data)).length));
			} catch (IOException e) {
				outcomes.add(e.getMessage());
			}
		}
		assertEquals(Arrays.asList(String.valueOf(ReceivedImageGate.MAX_BYTES),
				"image too large"), outcomes);
	}

	@Test
	public void savingOrOpeningChecksAnImageOfAnySizeTheSameWay()
			throws IOException {
		Decoder decoder = new Decoder();
		ReceivedImageGate.useDecoder(decoder);
		byte[] large = Arrays.copyOf(JPEG, 20 * 1024 * 1024);
		ReceivedImageGate.checkedIfImage(large);
		assertNull(ReceivedImageGate.decode(large, 512));
		assertEquals(0, decoder.seen.size());
		byte[] huge = Arrays.copyOf(jpeg(0xC0, 9000, 9000), 20 * 1024 * 1024);
		try {
			ReceivedImageGate.checkedIfImage(huge);
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

	private static final class Decoder
			implements ReceivedImageGate.PixelDecoder {

		private final List<byte[]> seen = new ArrayList<>();
		private final List<Integer> sides = new ArrayList<>();
		private int alone;
		private int released;
		@Nullable
		private Bitmap answer =
				Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);

		@Override
		@Nullable
		public Bitmap decodeAlone(byte[] checkedImage, int maxSide) {
			alone++;
			return decode(checkedImage, maxSide);
		}

		@Override
		public void release() {
			released++;
		}

		@Override
		@Nullable
		public Bitmap decode(byte[] checkedImage, int maxSide) {
			seen.add(checkedImage);
			sides.add(maxSide);
			return answer;
		}
	}

	@Test
	public void everyDecodeGoesThroughTheInstalledDecoderAfterTheCheck() {
		Decoder decoder = new Decoder();
		ReceivedImageGate.useDecoder(decoder);
		assertSame(decoder.answer, ReceivedImageGate.decode(JPEG, 512));
		for (byte[] refused : Arrays.asList(HEIC, AVIF, BMP, TIFF, MP4,
				jpeg(0xC0, 9000, 9000), png(1))) {
			assertNull(ReceivedImageGate.decode(refused, 512));
		}
		assertEquals(1, decoder.seen.size());
		assertEquals(Arrays.asList(512), decoder.sides);
		byte[] carrier = png(0, "gdAT");
		assertNotNull(ReceivedImageGate.decode(carrier, 64));
		assertArrayEquals(png(0), decoder.seen.get(1));
		byte[] pointed = jpegWithSegment(0xE2, "MPF\0II*");
		assertNotNull(ReceivedImageGate.decode(pointed, 64));
		assertEquals(0xEC, decoder.seen.get(2)[21] & 0xFF);
		assertSame(decoder.answer, ReceivedImageGate.decodeChecked(JPEG, 99));
		assertEquals(0, decoder.alone);
		assertSame(decoder.answer, ReceivedImageGate.decodeAlone(carrier, 77));
		assertArrayEquals(png(0), decoder.seen.get(4));
		assertNull(ReceivedImageGate.decodeAlone(HEIC, 77));
		assertEquals(Arrays.asList(512, 64, 64, 99, 77), decoder.sides);
		assertEquals(1, decoder.alone);
		ReceivedImageGate.releaseDecoder();
		assertEquals(1, decoder.released);
	}

	private static byte[] withScans(byte[] jpeg, int more, byte[] between) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, jpeg.length);
		byte[] scan = {(byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0, 0, 0x3F, 0,
				0x55};
		for (int i = 0; i < more; i++) {
			out.write(between, 0, between.length);
			out.write(scan, 0, scan.length);
		}
		out.write(0xFF);
		out.write(0xD9);
		return out.toByteArray();
	}

	@Test
	public void aJpegMayNotMakeTheDecoderWorkThroughMoreThanAHundredScans() {
		byte[] progressive = jpeg(0xC2);
		byte[] none = new byte[0];
		int limit = ReceivedImageGate.MAX_JPEG_SCANS;
		assertEquals("accepted", outcome(withScans(progressive, 9, none)));
		assertEquals("accepted",
				outcome(withScans(progressive, limit - 1, none)));
		assertEquals(NO_FORMAT, outcome(withScans(progressive, limit, none)));
		assertEquals(NO_FORMAT, outcome(withScans(jpeg(0xC0), limit, none)));
		byte[] stuffed = {(byte) 0xFF, 0, (byte) 0xFF, (byte) 0xD3,
				(byte) 0xFF, (byte) 0xFF};
		assertEquals(NO_FORMAT,
				outcome(withScans(progressive, limit, stuffed)));
		byte[] table = {(byte) 0xFF, (byte) 0xC4, 0, 4, 0, 0, (byte) 0xFF,
				(byte) 0xFE, 0, 3, 7};
		assertEquals(NO_FORMAT, outcome(withScans(progressive, limit, table)));
		byte[] afterTheEnd = withScans(progressive, 3, none);
		byte[] trailing = withScans(afterTheEnd, limit + 50, none);
		assertEquals("accepted", outcome(trailing));
		byte[][] hiding = {{(byte) 0xFF, (byte) 0xFE, 0, 2, 0x55},
				{(byte) 0xFF, 0x01, 0x55}, {0x55, 0x66},
				{(byte) 0xFF, (byte) 0xFE, 0, 2, (byte) 0xFF, 0},
				{(byte) 0xFF, (byte) 0xDC, 0, 4, 0, 1}};
		for (byte[] between : hiding) {
			assertEquals(Arrays.toString(between), NO_FORMAT,
					outcome(withScans(progressive, limit, between)));
			assertEquals(Arrays.toString(between), "accepted",
					outcome(withScans(progressive, 5, between)));
		}
		byte[][] neverRead = {{(byte) 0xFF, (byte) 0xFE, 0, 0},
				{(byte) 0xFF, (byte) 0xFE, 0, 1},
				{(byte) 0xFF, (byte) 0xC0, 0, 2},
				{(byte) 0xFF, (byte) 0xD8},
				{(byte) 0xFF, (byte) 0xF7, 0, 2}};
		for (byte[] between : neverRead) {
			assertEquals(Arrays.toString(between), NO_FORMAT,
					outcome(withScans(progressive, 2, between)));
		}
		byte[] cutShort = Arrays.copyOf(withScans(progressive, 5, none), 80);
		assertEquals("accepted", outcome(cutShort));
		byte[] endsOnMarkerByte = Arrays.copyOf(progressive,
				progressive.length + 1);
		endsOnMarkerByte[progressive.length] = (byte) 0xFF;
		assertEquals("accepted", outcome(endsOnMarkerByte));
	}

	private static final class Result
			implements DataFetcher.DataCallback<Bitmap> {

		@Nullable
		private Bitmap data;
		@Nullable
		private Exception failure;

		@Override
		public void onDataReady(@Nullable Bitmap bitmap) {
			data = bitmap;
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
		new ZerionDataFetcher(reader, Runnable::run, Runnable::run, header,
				320, 240, false).loadData(Priority.NORMAL, result);
		return result;
	}

	@Test
	public void theImageLoaderGetsPixelsFromTheDecoderAndNeverTheBytes() {
		Decoder decoder = new Decoder();
		ReceivedImageGate.useDecoder(decoder);
		Result jpeg = fetched(JPEG);
		assertSame(decoder.answer, jpeg.data);
		assertNull(jpeg.failure);
		assertEquals(Arrays.asList(400), decoder.sides);
		for (byte[] refused : Arrays.asList(HEIC, AVIF, BMP, TIFF, MP4)) {
			Result r = fetched(refused);
			assertNull(r.data);
			assertEquals(NO_FORMAT,
					r.failure == null ? null : r.failure.getMessage());
		}
		assertEquals(1, decoder.seen.size());
		decoder.answer = null;
		Result undecoded = fetched(JPEG);
		assertNull(undecoded.data);
		assertNotNull(undecoded.failure);
	}

	private static String animation(byte[] stored) {
		AttachmentHeader header = new AttachmentHeader(new GroupId(new byte[32]),
				new MessageId(new byte[32]), "image/gif");
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
		String[] outcome = new String[1];
		new ZerionGifLoader.Fetcher(reader, Runnable::run, header).loadData(
				Priority.NORMAL, new DataFetcher.DataCallback<CheckedGif>() {
					@Override
					public void onDataReady(@Nullable CheckedGif gif) {
						outcome[0] = gif == null ? "nothing"
								: "animation of " + gif.bytes.length;
					}

					@Override
					public void onLoadFailed(Exception e) {
						outcome[0] = e.getMessage();
					}
				});
		return outcome[0];
	}

	@Test
	public void onlyACheckedGifReachesTheAnimationDecoderInsideTheApp() {
		Decoder decoder = new Decoder();
		ReceivedImageGate.useDecoder(decoder);
		assertEquals("animation of " + GIF.length, animation(GIF));
		for (byte[] other : Arrays.asList(JPEG, PNG, WEBP)) {
			assertEquals("not an animation", animation(other));
		}
		for (byte[] refused : Arrays.asList(HEIC, MP4, gif(3, false),
				gif(ReceivedImageGate.MAX_GIF_FRAMES + 1, true))) {
			assertEquals(NO_FORMAT, animation(refused));
		}
		assertEquals(0, decoder.seen.size());
		assertEquals(Arrays.asList(true, true, false, false, false),
				Arrays.asList(ZerionGifLoader.isDeclaredGif("image/gif"),
						ZerionGifLoader.isDeclaredGif("Image/GIF; x=1"),
						ZerionGifLoader.isDeclaredGif("image/jpeg"),
						ZerionGifLoader.isDeclaredGif("image/gifx"),
						ZerionGifLoader.isDeclaredGif("")));
	}

	@Test
	public void theLoaderAsksForTheSizeItWillShow() {
		assertEquals(Arrays.asList(800, 2600, 4000, 2000, 4000, 2560, 400,
				64, 1600, 4096, 4096), Arrays.asList(
				ZerionDataFetcher.sideFor(360, 360, 1600, 900, false),
				ZerionDataFetcher.sideFor(1440, 1440, 5200, 3900, false),
				ZerionDataFetcher.sideFor(1080, 2400, 4000, 3000, false),
				ZerionDataFetcher.sideFor(1080, 2400, 4000, 3000, true),
				ZerionDataFetcher.sideFor(200, 75, 4000, 100, false),
				ZerionDataFetcher.sideFor(Integer.MIN_VALUE,
						Integer.MIN_VALUE, 4000, 3000, false),
				ZerionDataFetcher.sideFor(600, 800, 300, 400, false),
				ZerionDataFetcher.sideFor(10, 10, 5000, 5000, false),
				ZerionDataFetcher.sideFor(Integer.MIN_VALUE,
						Integer.MIN_VALUE, 1600, 900, true),
				ZerionDataFetcher.sideFor(360, 360, 16384, 2000, false),
				ZerionDataFetcher.sideFor(16384, 16384, 16384, 9000,
						false)));
		int[][] shown = {{360, 360, 1600, 900}, {360, 360, 1600, 1200},
				{600, 800, 3000, 4000}, {1080, 2400, 1441, 900},
				{512, 512, 1025, 700}, {1440, 1440, 5200, 3900},
				{360, 360, 7000, 5000}, {100, 100, 4099, 4099}};
		for (int[] s : shown) {
			int side = ZerionDataFetcher.sideFor(s[0], s[1], s[2], s[3],
					false);
			int sample = 1;
			while ((Math.max(s[2], s[3]) + sample - 1) / sample > side) {
				sample *= 2;
			}
			double scale = Math.max(s[0] / (double) s[2],
					s[1] / (double) s[3]);
			assertTrue(Arrays.toString(s),
					1.0 / sample >= Math.min(1, scale));
			assertTrue(Arrays.toString(s),
					sample == 1 || 1.0 / sample < 2 * scale);
		}
	}
}

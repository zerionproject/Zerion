package com.professor.zerion.android.attachment;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ImageScrubberTest {

	public static final byte[] MARKER = "ZtPlantedGps52.3702N4.8952E"
			.getBytes(StandardCharsets.US_ASCII);

	private interface Scrub {
		byte[] apply(byte[] d) throws IOException;
	}

	public static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	public static byte[] bytes(int... v) {
		byte[] b = new byte[v.length];
		for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
		return b;
	}

	public static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	static boolean contains(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private static void assertRefused(Scrub scrub, byte[]... inputs) {
		for (int i = 0; i < inputs.length; i++) {
			try {
				scrub.apply(inputs[i]);
				fail("malformed input " + i + " accepted");
			} catch (IOException expected) {
			}
		}
	}

	public static final byte[] SOI = bytes(0xFF, 0xD8);
	public static final byte[] EOI = bytes(0xFF, 0xD9);

	public static byte[] segment(int marker, byte[] payload) {
		int n = payload.length + 2;
		return concat(bytes(0xFF, marker, n >> 8, n & 0xFF), payload);
	}

	public static byte[] jfif() {
		return segment(0xE0, concat(ascii("JFIF\0"), bytes(1, 1, 0, 0, 1, 0,
				1, 0, 0)));
	}

	public static byte[] iccProfile() {
		return segment(0xE2, concat(ascii("ICC_PROFILE\0"), bytes(1, 1),
				ascii("Google Inc. 2016 "), MARKER, new byte[400]));
	}

	public static byte[] exif() {
		return segment(0xE1, concat(ascii("Exif\0\0MM\0*"), MARKER));
	}

	public static final byte[] DQT = segment(0xDB, concat(bytes(0),
			filled(64, 3)));
	public static final byte[] SOF = segment(0xC0, bytes(8, 0, 16, 0, 16, 3,
			1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1));
	public static final byte[] DHT = segment(0xC4, concat(bytes(0x00),
			filled(16, 1), filled(16, 5)));
	public static final byte[] DRI = segment(0xDD, bytes(0, 4));
	public static final byte[] SOS = segment(0xDA, bytes(3, 1, 0, 2, 0x11, 3,
			0x11, 0, 0x3F, 0));
	public static final byte[] SCAN = bytes(0x12, 0x34, 0xFF, 0x00, 0xE1,
			0x00, 0x10, 'E', 'x', 'i', 'f', 0, 0, 0xFF, 0xD0, 0x56, 0xE2,
			0x02, 0x00, 'I', 'C', 'C', 0xFF, 0xFF, 0x00, 0x78, 0xFF, 0xD7,
			0xFE, 0x9A);

	private static byte[] filled(int n, int v) {
		byte[] b = new byte[n];
		for (int i = 0; i < n; i++) b[i] = (byte) (v + i);
		return b;
	}

	public static byte[] cleanJpeg() {
		return concat(SOI, DQT, SOF, DHT, DRI, SOS, SCAN, EOI);
	}

	public static byte[] skiaStyleJpeg() {
		return concat(SOI, jfif(), iccProfile(), DQT, SOF, DHT, SOS, SCAN, EOI);
	}

	@Test
	public void aJpegKeepsOnlyItsTablesFrameAndScan() throws Exception {
		byte[] tagged = concat(SOI, jfif(), iccProfile(), exif(),
				segment(0xED, concat(ascii("Photoshop 3.0\0"), MARKER)),
				segment(0xEE, ascii("Adobe\0\0\0\0\0\0")),
				segment(0xFE, MARKER), DQT, SOF, DHT, DRI, SOS, SCAN, EOI,
				MARKER);
		byte[] clean = ImageScrubber.jpeg(tagged);
		assertArrayEquals(cleanJpeg(), clean);
		assertArrayEquals(clean, ImageScrubber.scrub(tagged));
		assertFalse(contains(clean, MARKER));
		assertFalse(contains(clean, ascii("ICC_PROFILE")));
		assertFalse(contains(clean, ascii("JFIF")));
		assertArrayEquals(clean, ImageScrubber.jpeg(clean));
	}

	@Test
	public void whatTheAndroidEncoderAddsIsRemoved() throws Exception {
		assertArrayEquals(concat(SOI, DQT, SOF, DHT, SOS, SCAN, EOI),
				ImageScrubber.scrub(skiaStyleJpeg()));
	}

	@Test
	public void scanBytesThatLookLikeMetadataAreKeptVerbatim()
			throws Exception {
		byte[] scan = concat(SCAN, ascii("Exif"), MARKER, bytes(0xFF, 0x00),
				bytes(0xE1), MARKER);
		byte[] in = concat(SOI, DQT, SOF, DHT, SOS, scan, EOI);
		byte[] out = ImageScrubber.jpeg(in);
		assertArrayEquals(in, out);
		assertTrue(contains(out, MARKER));
	}

	@Test
	public void segmentsBetweenAndAfterScansAreDropped() throws Exception {
		byte[] second = bytes(0x55, 0xFF, 0x00, 0x66, 0xFF, 0xD1, 0x77);
		byte[] in = concat(SOI, DQT, SOF, DHT, SOS, SCAN,
				segment(0xE1, MARKER), DHT, SOS, second,
				segment(0xFE, MARKER), segment(0xEF, MARKER), EOI);
		assertArrayEquals(concat(SOI, DQT, SOF, DHT, SOS, SCAN, DHT, SOS,
				second, EOI), ImageScrubber.jpeg(in));
	}

	@Test
	public void fillBytesBeforeMarkersAreAccepted() throws Exception {
		byte[] in = concat(SOI, bytes(0xFF, 0xFF), DQT, bytes(0xFF), SOF,
				bytes(0xFF, 0xFF, 0xFF), jfif(), DHT, SOS, SCAN,
				bytes(0xFF, 0xFF), EOI);
		assertArrayEquals(concat(SOI, DQT, SOF, DHT, SOS, SCAN, EOI),
				ImageScrubber.jpeg(in));
	}

	@Test
	public void malformedJpegsAreRefused() {
		byte[] full = cleanJpeg();
		assertRefused(ImageScrubber::jpeg,
				concat(SOI, bytes(0xFF, 0xDB, 0x00)),
				concat(SOI, bytes(0xFF, 0xE1, 0x40, 0x00), MARKER),
				concat(SOI, bytes(0xFF, 0xE1, 0x00, 0x01), DQT, SOF, DHT, SOS,
						SCAN, EOI),
				concat(SOI, DQT, SOF, DHT, SOS, SCAN),
				concat(SOI, DQT, SOF, DHT, SOS, bytes(0x12, 0xFF)),
				concat(SOI, DQT, SOF, DHT, SOS, bytes(0x12, 0xFF, 0xFF)),
				concat(SOI, DQT, bytes(0x00), SOF, DHT, SOS, SCAN, EOI),
				concat(SOI, DQT, DHT, SOS, SCAN, EOI),
				concat(SOI, DQT, SOF, EOI),
				concat(SOI, EOI),
				concat(SOI, DQT, SOF, SOF, DHT, SOS, SCAN, EOI),
				concat(SOI, segment(0xC8, bytes(1, 2)), DQT, SOF, DHT, SOS,
						SCAN, EOI),
				concat(SOI, segment(0xDC, bytes(0, 16)), DQT, SOF, DHT, SOS,
						SCAN, EOI),
				concat(SOI, bytes(0xFF, 0xD0), DQT, SOF, DHT, SOS, SCAN, EOI),
				concat(SOI, SOI, DQT, SOF, DHT, SOS, SCAN, EOI),
				concat(SOI, bytes(0xFF)),
				concat(SOI, bytes(0xFF, 0xFF, 0xFF)),
				SOI,
				bytes(0xFF, 0xD8, 0x00),
				java.util.Arrays.copyOf(full, full.length - 1),
				pngWith());
	}

	public static final byte[] PNG_SIGNATURE = bytes(0x89, 'P', 'N', 'G',
			0x0D, 0x0A, 0x1A, 0x0A);

	public static byte[] chunk(String type, byte[] data) {
		CRC32 crc = new CRC32();
		crc.update(ascii(type));
		crc.update(data);
		return concat(be32(data.length), ascii(type), data,
				be32(crc.getValue()));
	}

	public static byte[] be32(long v) {
		return bytes((int) (v >> 24), (int) (v >> 16), (int) (v >> 8),
				(int) v);
	}

	public static final byte[] IHDR = chunk("IHDR", bytes(0, 0, 0, 2, 0, 0,
			0, 2, 8, 3, 0, 0, 0));
	public static final byte[] PLTE = chunk("PLTE", bytes(255, 0, 0, 0, 0,
			255));
	public static final byte[] TRNS = chunk("tRNS", bytes(0x80));
	public static final byte[] IDAT_1 = chunk("IDAT", bytes(0x78, 0x9C, 0x63,
			0x60));
	public static final byte[] IDAT_2 = chunk("IDAT", bytes(0x60, 0x00, 0x00,
			0x00, 0x06, 0x00, 0x02));
	public static final byte[] IEND = chunk("IEND", new byte[0]);

	public static byte[] cleanPng() {
		return concat(PNG_SIGNATURE, IHDR, PLTE, TRNS, IDAT_1, IDAT_2, IEND);
	}

	public static byte[] pngWith(byte[]... ancillary) {
		return concat(PNG_SIGNATURE, IHDR, concat(ancillary), PLTE, TRNS,
				IDAT_1, IDAT_2, IEND);
	}

	@Test
	public void aPngKeepsOnlyItsImageChunks() throws Exception {
		byte[] badCrc = chunk("tEXt", concat(ascii("Comment\0"), MARKER));
		badCrc[badCrc.length - 1] ^= 0x55;
		byte[] tagged = concat(PNG_SIGNATURE, IHDR,
				chunk("iCCP", concat(ascii("ICC Profile\0\0"), MARKER)),
				chunk("sRGB", bytes(0)), chunk("gAMA", bytes(0, 0, 0xB1, 0x8F)),
				chunk("cHRM", new byte[32]), chunk("pHYs", new byte[9]),
				chunk("tEXt", concat(ascii("Software\0"), MARKER)), badCrc,
				chunk("zTXt", concat(ascii("Raw\0\0"), MARKER)),
				chunk("iTXt", concat(ascii("XML:com.adobe.xmp\0\0\0\0\0"),
						MARKER)),
				chunk("eXIf", concat(ascii("MM\0*"), MARKER)),
				chunk("tIME", bytes(7, 0xEA, 10, 15, 12, 0, 0)), PLTE, TRNS,
				chunk("bKGD", bytes(0)), IDAT_1,
				chunk("zzZz", MARKER), IDAT_2,
				chunk("tEXt", MARKER), IEND, MARKER);
		byte[] clean = ImageScrubber.png(tagged);
		assertArrayEquals(cleanPng(), clean);
		assertArrayEquals(clean, ImageScrubber.scrub(tagged));
		assertFalse(contains(clean, MARKER));
		assertArrayEquals(clean, ImageScrubber.png(clean));
	}

	@Test
	public void malformedPngsAreRefused() {
		byte[] full = cleanPng();
		byte[] badIdat = cleanPng();
		badIdat[PNG_SIGNATURE.length + IHDR.length + PLTE.length
				+ TRNS.length + 9] ^= 0x01;
		byte[] badType = chunk("tE1t", MARKER);
		assertRefused(ImageScrubber::png,
				concat(PNG_SIGNATURE, IHDR, bytes(0x80, 0, 0, 0), ascii("tEXt"),
						MARKER, IDAT_1, IEND),
				concat(PNG_SIGNATURE, IHDR, bytes(0, 0, 0x10, 0),
						ascii("tEXt"), MARKER, IDAT_1, IEND),
				concat(PNG_SIGNATURE, IHDR, IDAT_1),
				java.util.Arrays.copyOf(full, full.length - 1),
				badIdat,
				concat(PNG_SIGNATURE, chunk("iCCP", MARKER), IHDR, IDAT_1,
						IEND),
				concat(PNG_SIGNATURE, IHDR, IHDR, IDAT_1, IEND),
				concat(PNG_SIGNATURE, IHDR, chunk("CgBI", bytes(0x50, 0, 0x20,
						2)), IDAT_1, IEND),
				concat(PNG_SIGNATURE, IHDR, badType, IDAT_1, IEND),
				concat(PNG_SIGNATURE, IHDR, PLTE, IEND),
				concat(PNG_SIGNATURE, IDAT_1, IEND),
				PNG_SIGNATURE,
				concat(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x00),
						IHDR, IDAT_1, IEND),
				cleanJpeg());
	}

	public static byte[] le32(long v) {
		return bytes((int) v, (int) (v >> 8), (int) (v >> 16),
				(int) (v >> 24));
	}

	public static byte[] riffChunk(String fourCc, byte[] data) {
		byte[] pad = (data.length & 1) == 1 ? new byte[1] : new byte[0];
		return concat(ascii(fourCc), le32(data.length), data, pad);
	}

	public static byte[] webp(byte[]... chunks) {
		byte[] body = concat(chunks);
		return concat(ascii("RIFF"), le32(4 + body.length), ascii("WEBP"),
				body);
	}

	public static byte[] vp8x(int flags, int reserved) {
		return riffChunk("VP8X", bytes(flags, reserved, reserved, reserved,
				99, 0, 0, 74, 0, 0));
	}

	public static final byte[] VP8 = riffChunk("VP8 ", bytes(0x30, 0x01,
			0x00, 0x9D, 0x01, 0x2A, 100, 0, 75, 0, 0x11));
	public static final byte[] VP8L = riffChunk("VP8L", bytes(0x2F, 99, 0x40,
			0x12, 0x00, 0x07, 0x10));
	public static final byte[] ALPH = riffChunk("ALPH", bytes(0x00, 0xFF,
			0xFE, 0xFD));

	@Test
	public void aWebpKeepsOnlyItsImageChunksWithCleanFlags()
			throws Exception {
		byte[] tagged = webp(vp8x(0x3C | 0xC1, 0x7A),
				riffChunk("ICCP", concat(ascii("lcms"), MARKER)), ALPH, VP8,
				riffChunk("EXIF", concat(ascii("MM\0*"), MARKER)),
				riffChunk("XMP ", MARKER), riffChunk("ZtZt", MARKER));
		byte[] withTrailer = concat(tagged, MARKER);
		byte[] clean = ImageScrubber.webp(withTrailer);
		byte[] expected = webp(vp8x(0x10, 0), ALPH, VP8);
		assertArrayEquals(expected, clean);
		assertArrayEquals(clean, ImageScrubber.scrub(withTrailer));
		assertEquals(clean.length - 8, (clean[4] & 0xFF)
				| ((clean[5] & 0xFF) << 8));
		assertFalse(contains(clean, MARKER));
		assertArrayEquals(clean, ImageScrubber.webp(clean));
	}

	@Test
	public void aSimpleWebpIsKeptAsItIs() throws Exception {
		byte[] lossless = webp(VP8L);
		assertArrayEquals(lossless, ImageScrubber.webp(lossless));
		byte[] lossy = webp(VP8);
		assertArrayEquals(lossy, ImageScrubber.scrub(lossy));
	}

	@Test
	public void framesOfAnAnimatedWebpLoseHiddenChunks() throws Exception {
		byte[] header = bytes(0, 0, 0, 0, 0, 0, 99, 0, 0, 74, 0, 0, 100, 0, 0,
				2);
		byte[] anim = riffChunk("ANIM", bytes(0, 0, 0, 0, 0, 0));
		byte[] tagged = webp(vp8x(0x02 | 0x08 | 0x10, 0), anim,
				riffChunk("ANMF", concat(header, ALPH, riffChunk("ZtZt",
						MARKER), VP8)),
				riffChunk("ANMF", concat(header, VP8L)),
				riffChunk("EXIF", MARKER));
		byte[] expected = webp(vp8x(0x02 | 0x10, 0), anim,
				riffChunk("ANMF", concat(header, ALPH, VP8)),
				riffChunk("ANMF", concat(header, VP8L)));
		assertArrayEquals(expected, ImageScrubber.webp(tagged));
	}

	@Test
	public void malformedWebpsAreRefused() {
		byte[] full = webp(VP8);
		byte[] tooLong = full.clone();
		tooLong[4] = (byte) (tooLong[4] + 2);
		byte[] chunkPastEnd = webp(concat(ascii("VP8 "), le32(400),
				bytes(1, 2, 3, 4)));
		byte[] header = new byte[16];
		assertRefused(ImageScrubber::webp,
				tooLong,
				chunkPastEnd,
				java.util.Arrays.copyOf(full, full.length - 1),
				webp(VP8, vp8x(0, 0)),
				webp(riffChunk("VP8X", new byte[8]), VP8),
				webp(vp8x(0x20, 0), riffChunk("ICCP", MARKER)),
				webp(VP8, bytes('A', 'B', 'C', 'D')),
				webp(vp8x(0x02, 0), riffChunk("ANMF", new byte[10])),
				webp(vp8x(0x02, 0), riffChunk("ANMF", concat(header,
						riffChunk("XMP ", MARKER)))),
				webp(vp8x(0x02, 0), riffChunk("ANMF", concat(header,
						riffChunk("ANIM", new byte[6]), VP8))),
				concat(ascii("RIFF"), le32(2), ascii("WEBP")),
				concat(ascii("RIFF"), le32(4), ascii("WAVE"), VP8),
				cleanPng());
	}

	@Test
	public void onlyStillImageFormatsTheEncoderWritesAreAccepted() {
		assertRefused(ImageScrubber::scrub,
				new byte[0],
				bytes(0xFF),
				ascii("GIF89a\1\0\1\0\0\0\0;"),
				ascii("BM6\0\0\0\0\0\0\0"),
				concat(new byte[4], ascii("ftypheic"), new byte[8]),
				MARKER);
	}
}

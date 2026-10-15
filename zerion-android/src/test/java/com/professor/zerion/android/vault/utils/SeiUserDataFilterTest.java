package com.professor.zerion.android.vault.utils;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeiUserDataFilterTest {

	private static final String AVC = "video/avc";
	private static final String HEVC = "video/hevc";

	private static final byte[] SC4 = {0, 0, 0, 1};
	private static final byte[] SC3 = {0, 0, 1};

	private static final byte[] X264_UUID = bytes(0xDC, 0x45, 0xE9, 0xBD,
			0xE6, 0xD9, 0x48, 0xB7, 0x96, 0x2C, 0xD8, 0x20, 0xD9, 0x23, 0xEE,
			0xEF);
	static final byte[] X264 = ascii("x264 - core 164 r3095 baee400 - "
			+ "H.264/MPEG-4 AVC codec - Copyleft 2003-2022 - "
			+ "http://www.videolan.org/x264.html - options: cabac=1 ref=3 "
			+ "deblock=1:0:0 analyse=0x3:0x113 me=hex subme=7 psy=1 "
			+ "psy_rd=1.00:0.00 mixed_ref=1 me_range=16 chroma_me=1 "
			+ "trellis=1 8x8dct=1 cqm=0 deadzone=21,11 fast_pskip=1 "
			+ "chroma_qp_offset=-2 threads=12 lookahead_threads=2");
	private static final byte[] X265 = ascii("x265 (build 199) - "
			+ "3.5+1-f0c1022b6:[Linux][GCC 11.2.0][64 bit] 8bit+10bit+12bit - "
			+ "H.265/HEVC "
			+ "codec - Copyright 2013-2018 (c) Multicoreware, Inc - "
			+ "http://x265.org - options: cpuid=1111039 frame-threads=3");

	private static final byte[] AUD = bytes(0x09, 0xF0);
	private static final byte[] SPS = bytes(0x67, 0x64, 0x00, 0x1F, 0xAC,
			0xD9, 0x40, 0x50, 0x05, 0xBB, 0x01, 0x10);
	private static final byte[] PPS = bytes(0x68, 0xEB, 0xE3, 0xCB, 0x22,
			0xC0);
	private static final byte[] IDR = bytes(0x65, 0x88, 0x84, 0x00, 0x33,
			0xFF, 0xFE, 0xF6, 0xF0, 0xFE, 0x05, 0x36, 0x56, 0x04, 0x50);

	private static final byte[] HEVC_VPS = bytes(0x40, 0x01, 0x0C, 0x01, 0xFF,
			0xFF, 0x01, 0x60);
	private static final byte[] HEVC_SPS = bytes(0x42, 0x01, 0x01, 0x01, 0x60,
			0x00, 0x00, 0x03, 0x00, 0x90);
	private static final byte[] HEVC_PPS = bytes(0x44, 0x01, 0xC1, 0x72,
			0xB4, 0x62, 0x40);
	private static final byte[] HEVC_IDR = bytes(0x26, 0x01, 0xAF, 0x06, 0xB8,
			0x63, 0xEF, 0x3A, 0x7F, 0x3C);

	static byte[] bytes(int... v) {
		byte[] b = new byte[v.length];
		for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
		return b;
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
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

	private static void codedNumber(ByteArrayOutputStream out, int value) {
		while (value >= 0xFF) {
			out.write(0xFF);
			value -= 0xFF;
		}
		out.write(value);
	}

	static byte[] message(int type, byte[] payload) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		codedNumber(out, type);
		codedNumber(out, payload.length);
		out.write(payload, 0, payload.length);
		return out.toByteArray();
	}

	static byte[] escaped(byte[] rbsp) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int zeros = 0;
		for (byte value : rbsp) {
			int b = value & 0xFF;
			if (zeros >= 2 && b <= 3) {
				out.write(3);
				zeros = 0;
			}
			out.write(b);
			zeros = b == 0 ? zeros + 1 : 0;
		}
		return out.toByteArray();
	}

	static byte[] sei(byte[] header, byte[]... messages) {
		return concat(header, escaped(concat(concat(messages),
				bytes(0x80))));
	}

	static byte[] x264UserData() {
		return message(5, concat(X264_UUID, X264, bytes(0)));
	}

	private static byte[] filtered(String mime, byte[] sample) {
		byte[] d = sample.clone();
		int n = SeiUserDataFilter.filter(mime, d, 0, d.length);
		return Arrays.copyOf(d, n);
	}

	private static void assertUnchanged(String mime, byte[] sample) {
		assertArrayEquals(sample, filtered(mime, sample));
	}

	@Test
	public void x264UserDataIsDroppedAndEveryOtherUnitIsKeptByteForByte() {
		byte[] userData = sei(bytes(0x06), x264UserData());
		assertTrue("the test needs a size above 255",
				X264.length + 17 > 255);
		byte[] sample = concat(SC4, AUD, SC4, SPS, SC4, PPS, SC4, userData,
				SC4, IDR);
		byte[] out = filtered(AVC, sample);
		assertArrayEquals(concat(SC4, AUD, SC4, SPS, SC4, PPS, SC4, IDR), out);
		assertFalse(contains(out, ascii("x264")));
	}

	@Test
	public void otherSeiMessagesAreKept() {
		byte[] recoveryPoint = message(6, bytes(0x84));
		byte[] picTiming = message(1, bytes(0x00, 0x00, 0x00, 0x08, 0x00));
		byte[] masteringDisplay = message(137, new byte[24]);
		byte[] lightLevel = message(144, bytes(0x03, 0xE8, 0x01, 0x90));
		byte[] captions = message(4, concat(bytes(0xB5, 0x00, 0x31),
				ascii("GA94"), bytes(0x03, 0xC1, 0xFF, 0xFC, 0x80, 0x80)));
		byte[] sample = concat(SC4,
				sei(bytes(0x06), recoveryPoint, picTiming), SC4,
				sei(bytes(0x06), masteringDisplay, lightLevel), SC4,
				sei(bytes(0x06), captions), SC4, IDR);
		assertUnchanged(AVC, sample);
	}

	@Test
	public void aUnitWithUserDataAmongOtherMessagesIsDroppedWhole() {
		byte[] mixed = sei(bytes(0x06), message(6, bytes(0x84)),
				x264UserData(), message(1, bytes(0x10, 0x20)));
		byte[] sample = concat(SC4, SPS, SC4, mixed, SC4, IDR);
		assertArrayEquals(concat(SC4, SPS, SC4, IDR), filtered(AVC, sample));
	}

	@Test
	public void payloadTypesAndSizesUseTheirContinuationBytes() {
		byte[] type261 = sei(bytes(0x06), message(261, bytes(1, 2, 3)));
		assertEquals(0xFF, type261[1] & 0xFF);
		assertEquals(0x06, type261[2] & 0xFF);
		byte[] type515 = sei(bytes(0x06), message(515, bytes(4, 5)));
		assertUnchanged(AVC, concat(SC4, type261, SC4, type515, SC4, IDR));

		byte[] longPayload = new byte[600];
		for (int i = 0; i < longPayload.length; i++) {
			longPayload[i] = (byte) (i % 7 == 0 ? 5 : 0x41);
		}
		byte[] longThenUserData = sei(bytes(0x06), message(1, longPayload),
				message(5, concat(X264_UUID, ascii("private"))));
		byte[] longOnly = sei(bytes(0x06), message(1, longPayload));
		byte[] sample = concat(SC4, longThenUserData, SC4, longOnly, SC4, IDR);
		assertArrayEquals(concat(SC4, longOnly, SC4, IDR),
				filtered(AVC, sample));
	}

	@Test
	public void emulationPreventionBytesAreRemovedBeforeTheMessagesAreRead() {
		byte[] zeros = sei(bytes(0x06), message(6, bytes(0, 0, 0, 7)),
				message(5, bytes(0x41, 0x42, 0x43, 0x44)));
		assertTrue(contains(zeros, bytes(0, 0, 3, 0, 7, 5, 4)));
		assertArrayEquals(concat(SC4, IDR),
				filtered(AVC, concat(SC4, zeros, SC4, IDR)));

		byte[] lookalike = sei(bytes(0x06), message(1, bytes(0, 0, 1, 5)),
				message(4, bytes(0xB5, 0, 0x31, 9)));
		assertTrue(contains(lookalike, bytes(0, 0, 3, 1, 5)));
		assertUnchanged(AVC, concat(SC4, lookalike, SC4, IDR));
	}

	@Test
	public void startCodeLikeBytesInsideAUnitDoNotSplitIt() {
		byte[] slice = concat(bytes(0x65, 0x88),
				escaped(bytes(0, 0, 1, 0x06, 0x05, 0x10, 0, 0, 0, 0x06, 0x05,
						0x02, 0x41, 0x42, 0x80)), bytes(0x11, 0x22));
		assertTrue(contains(slice, bytes(0, 0, 3, 1, 0x06, 0x05)));
		byte[] sample = concat(SC4, sei(bytes(0x06), x264UserData()), SC4,
				slice);
		assertArrayEquals(concat(SC4, slice), filtered(AVC, sample));
		assertUnchanged(AVC, concat(SC4, SPS, SC4, slice));
	}

	@Test
	public void threeAndFourByteStartCodesAreBothFollowed() {
		byte[] userData = sei(bytes(0x06), x264UserData());
		assertArrayEquals(concat(SC3, IDR),
				filtered(AVC, concat(SC3, userData, SC3, IDR)));
		assertArrayEquals(concat(SC4, SPS, SC3, PPS, SC4, IDR),
				filtered(AVC, concat(SC4, SPS, SC3, PPS, SC3, userData, SC4,
						IDR)));
		assertArrayEquals(concat(SC4, SPS, new byte[3], SC3, IDR),
				filtered(AVC, concat(SC4, SPS, new byte[2], SC3, userData,
						new byte[3], SC3, IDR)));
		assertArrayEquals(concat(SC4, IDR),
				filtered(AVC, concat(SC4, IDR, SC3, userData, new byte[2])));
		assertArrayEquals(concat(new byte[3], SC4, IDR),
				filtered(AVC, concat(new byte[3], SC4, IDR)));
	}

	@Test
	public void seiThatCannotBeReadToItsEndIsDroppedEveryTime() {
		byte[] sizePastEnd = concat(bytes(0x06), bytes(1, 40, 1, 2, 3, 0x80));
		byte[] noStopBit = concat(bytes(0x06), bytes(1, 2, 7, 8));
		byte[] cutType = concat(bytes(0x06), bytes(0xFF, 0xFF, 0x80));
		byte[] cutSize = concat(bytes(0x06), bytes(1, 0x80));
		byte[] headerOnly = bytes(0x06);
		for (byte[] broken : new byte[][] {sizePastEnd, noStopBit, cutType,
				cutSize, headerOnly}) {
			byte[] sample = concat(SC4, SPS, SC4, broken, SC4, IDR);
			assertArrayEquals(Arrays.toString(broken), concat(SC4, SPS, SC4,
					IDR), filtered(AVC, sample));
		}
		byte[] empty = concat(bytes(0x06), bytes(0x80));
		assertUnchanged(AVC, concat(SC4, empty, SC4, IDR));
	}

	@Test
	public void hevcPrefixAndSuffixUserDataAreDropped() {
		byte[] prefix = sei(bytes(0x4E, 0x01), message(5,
				concat(new byte[16], X265, bytes(0))));
		byte[] suffix = sei(bytes(0x50, 0x01), message(5,
				concat(new byte[16], ascii("vendor"))));
		byte[] hdr = sei(bytes(0x4E, 0x01), message(137, new byte[24]),
				message(144, bytes(0x03, 0xE8, 0x01, 0x90)));
		byte[] hash = sei(bytes(0x50, 0x01), message(132, concat(bytes(1),
				new byte[16])));
		byte[] sample = concat(SC4, HEVC_VPS, SC4, HEVC_SPS, SC4, HEVC_PPS,
				SC4, prefix, SC4, hdr, SC4, HEVC_IDR, SC4, suffix, SC4, hash);
		byte[] out = filtered(HEVC, sample);
		assertArrayEquals(concat(SC4, HEVC_VPS, SC4, HEVC_SPS, SC4, HEVC_PPS,
				SC4, hdr, SC4, HEVC_IDR, SC4, hash), out);
		assertFalse(contains(out, ascii("x265")));
	}

	@Test
	public void theUnitTypeIsReadTheWayTheCodecDefinesIt() {
		byte[] hevcSei = sei(bytes(0x4E, 0x01), x264UserData());
		assertUnchanged(AVC, concat(SC4, hevcSei, SC4, IDR));
		byte[] avcSei = sei(bytes(0x06), x264UserData());
		assertUnchanged(HEVC, concat(SC4, avcSei, SC4, HEVC_IDR));
	}

	@Test
	public void otherCodecsAndSamplesWithoutStartCodesAreLeftAlone() {
		byte[] sample = concat(SC4, sei(bytes(0x06), x264UserData()), SC4,
				IDR);
		for (String mime : new String[] {"video/x-vnd.on2.vp9",
				"video/av01", "video/mp4v-es", "audio/mp4a-latm"}) {
			assertUnchanged(mime, sample);
		}
		byte[] d = sample.clone();
		assertEquals(d.length, SeiUserDataFilter.filter(null, d, 0,
				d.length));
		assertArrayEquals(sample, d);
		byte[] lengthPrefixed = concat(bytes(0, 0, 0x02, 0x10),
				sei(bytes(0x06), x264UserData()));
		assertUnchanged(AVC, lengthPrefixed);
		byte[] junkFirst = concat(bytes(0x42), SC4, sei(bytes(0x06),
				x264UserData()), SC4, IDR);
		assertUnchanged(AVC, junkFirst);
	}

	@Test
	public void theSampleBufferIsCompactedWhereverItsBytesLive() {
		byte[] sample = concat(SC4, SPS, SC4, sei(bytes(0x06), x264UserData()),
				SC4, IDR);
		byte[] expected = concat(SC4, SPS, SC4, IDR);

		ByteBuffer heap = ByteBuffer.allocate(4096);
		heap.put(sample);
		int n = SeiUserDataFilter.filter(AVC, heap, sample.length);
		assertEquals(expected.length, n);
		assertArrayEquals(expected, Arrays.copyOf(heap.array(), n));

		byte[] backing = new byte[sample.length + 10];
		System.arraycopy(sample, 0, backing, 10, sample.length);
		ByteBuffer slice = ByteBuffer.wrap(backing, 10, sample.length).slice();
		n = SeiUserDataFilter.filter(AVC, slice, sample.length);
		assertEquals(expected.length, n);
		assertArrayEquals(expected, Arrays.copyOfRange(backing, 10, 10 + n));

		ByteBuffer direct = ByteBuffer.allocateDirect(4096);
		direct.put(sample);
		direct.position(7);
		n = SeiUserDataFilter.filter(AVC, direct, sample.length);
		assertEquals(expected.length, n);
		byte[] got = new byte[n];
		ByteBuffer view = direct.duplicate();
		view.position(0);
		view.get(got);
		assertArrayEquals(expected, got);
		assertEquals(7, direct.position());
	}
}

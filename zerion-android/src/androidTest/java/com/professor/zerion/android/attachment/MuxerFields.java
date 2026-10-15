package com.professor.zerion.android.attachment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

final class MuxerFields {

	private static final long SEGMENT = 0x18538067L;
	private static final long INFO = 0x1549A966L;
	private static final long CLUSTER = 0x1F43B675L;
	private static final long MUXING_APP = 0x4D80L;
	private static final long WRITING_APP = 0x5741L;

	private MuxerFields() {
	}

	private static long u32(byte[] d, int off) {
		return ((d[off] & 0xFFL) << 24) | ((d[off + 1] & 0xFFL) << 16)
				| ((d[off + 2] & 0xFFL) << 8) | (d[off + 3] & 0xFFL);
	}

	private static boolean zero(byte[] d, int from, int to) {
		for (int i = from; i < to; i++) {
			if (d[i] != 0) return false;
		}
		return true;
	}

	static boolean hasMp4WriterFields(byte[] mp4) {
		int[] counts = new int[2];
		walkMp4(mp4, 0, mp4.length, counts, false);
		return counts[1] > 0;
	}

	static void assertMp4WriterFieldsCleared(String what, byte[] mp4) {
		int[] counts = new int[2];
		walkMp4(mp4, 0, mp4.length, counts, true);
		assertTrue(what + ": no movie, track or media header found",
				counts[0] >= 3);
	}

	private static void walkMp4(byte[] d, int from, int to, int[] counts,
			boolean assertCleared) {
		int pos = from;
		while (pos + 8 <= to) {
			long size = u32(d, pos);
			int header = 8;
			if (size == 1) {
				size = (u32(d, pos + 8) << 32) | u32(d, pos + 12);
				header = 16;
			} else if (size == 0) {
				size = to - pos;
			}
			assertTrue("box size at " + pos, size >= header
					&& pos + size <= to);
			int end = (int) (pos + size);
			String type = new String(d, pos + 4, 4,
					StandardCharsets.ISO_8859_1);
			int body = pos + header;
			if (type.equals("mvhd") || type.equals("tkhd")
					|| type.equals("mdhd")) {
				counts[0]++;
				int time = d[body] == 0 ? 4 : 8;
				boolean cleared = zero(d, body + 4, body + 4 + 2 * time);
				if (type.equals("mdhd")) {
					int language = body + 4 + 3 * time + 4;
					cleared &= zero(d, language, language + 2);
				}
				if (!cleared) counts[1]++;
				if (assertCleared) {
					assertTrue(type + " still carries the time it was written",
							cleared);
				}
			} else if (type.equals("hdlr")) {
				boolean blank = zero(d, body + 24, end);
				if (!blank) counts[1]++;
				if (assertCleared) {
					assertTrue("hdlr still carries the writer's handler name",
							blank);
				}
			} else if (type.equals("moov") || type.equals("trak")
					|| type.equals("mdia") || type.equals("minf")) {
				walkMp4(d, body, end, counts, assertCleared);
			}
			pos = end;
		}
	}

	private static final class Element {
		long id;
		int data;
		int end;
	}

	private static Element element(byte[] d, int pos, int limit) {
		int first = d[pos] & 0xFF;
		int idLength = Integer.numberOfLeadingZeros(first) - 23;
		assertTrue("element id at " + pos, idLength >= 1 && idLength <= 4);
		Element e = new Element();
		for (int i = 0; i < idLength; i++) {
			e.id = (e.id << 8) | (d[pos + i] & 0xFF);
		}
		int s = d[pos + idLength] & 0xFF;
		int sizeLength = Integer.numberOfLeadingZeros(s) - 23;
		assertTrue("element size at " + pos, sizeLength >= 1
				&& sizeLength <= 8);
		int mask = 0xFF >> sizeLength;
		long size = s & mask;
		boolean unknown = size == mask;
		for (int i = 1; i < sizeLength; i++) {
			int b = d[pos + idLength + i] & 0xFF;
			size = (size << 8) | b;
			if (b != 0xFF) unknown = false;
		}
		e.data = pos + idLength + sizeLength;
		e.end = unknown ? limit : (int) Math.min(limit, e.data + size);
		return e;
	}

	private static boolean mentionsWriter(byte[] d, int from, int to) {
		byte[] lower = new byte[to - from];
		for (int i = from; i < to; i++) {
			int c = d[i];
			if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
			lower[i - from] = (byte) c;
		}
		return DeviceMediaFiles.contains(lower,
				"android".getBytes(StandardCharsets.US_ASCII));
	}

	static boolean hasWebmWriterName(byte[] webm) {
		return mentionsWriter(webm, 0, webm.length);
	}

	static void assertWebmWriterFieldsBlank(String what, byte[] webm) {
		Element head = element(webm, 0, webm.length);
		Element segment = element(webm, head.end, webm.length);
		assertEquals(what + ": no segment", SEGMENT, segment.id);
		int apps = 0;
		int pos = segment.data;
		while (pos < segment.end) {
			Element e = element(webm, pos, segment.end);
			if (e.id == INFO) {
				int at = e.data;
				while (at < e.end) {
					Element field = element(webm, at, e.end);
					if (field.id == MUXING_APP || field.id == WRITING_APP) {
						apps++;
						assertTrue(what + ": the muxer's writer field is not"
								+ " blank", zero(webm, field.data, field.end));
					}
					at = field.end;
				}
			}
			if (e.id != CLUSTER) {
				assertFalse(what + ": the writer name is still in the file",
						mentionsWriter(webm, pos, e.end));
			}
			pos = e.end;
		}
		assertTrue(what + ": no writer fields found", apps > 0);
	}
}

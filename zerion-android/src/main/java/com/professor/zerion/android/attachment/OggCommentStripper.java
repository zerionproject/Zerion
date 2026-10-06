package com.professor.zerion.android.attachment;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.professor.zerion.android.attachment.AudioTagStripper.matches;
import static com.professor.zerion.android.attachment.AudioTagStripper.u32le;

@NotNullByDefault
final class OggCommentStripper {

	private static final int HEADER = 27;
	private static final int MAX_SEGMENTS = 255;
	private static final int CONTINUED = 0x01;
	private static final int FIRST = 0x02;
	private static final int LAST = 0x04;
	private static final long NO_GRANULE = -1L;

	private static final int COPY = 0;
	private static final int VORBIS = 1;
	private static final int OPUS = 2;
	private static final int THEORA = 3;
	private static final int SPEEX = 4;
	private static final int FLAC = 5;

	private static final byte[] VORBIS_ID = {1, 'v', 'o', 'r', 'b', 'i', 's'};
	private static final byte[] VORBIS_COMMENT =
			{3, 'v', 'o', 'r', 'b', 'i', 's'};
	private static final byte[] VORBIS_SETUP =
			{5, 'v', 'o', 'r', 'b', 'i', 's'};
	private static final byte[] EMPTY_VORBIS_COMMENT =
			{3, 'v', 'o', 'r', 'b', 'i', 's', 0, 0, 0, 0, 0, 0, 0, 0, 1};
	private static final byte[] OPUS_ID =
			{'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'};
	private static final byte[] OPUS_COMMENT =
			{'O', 'p', 'u', 's', 'T', 'a', 'g', 's'};
	private static final byte[] EMPTY_OPUS_COMMENT =
			{'O', 'p', 'u', 's', 'T', 'a', 'g', 's', 0, 0, 0, 0, 0, 0, 0, 0};
	private static final byte[] THEORA_ID =
			{(byte) 0x80, 't', 'h', 'e', 'o', 'r', 'a'};
	private static final byte[] THEORA_COMMENT =
			{(byte) 0x81, 't', 'h', 'e', 'o', 'r', 'a'};
	private static final byte[] THEORA_SETUP =
			{(byte) 0x82, 't', 'h', 'e', 'o', 'r', 'a'};
	private static final byte[] EMPTY_THEORA_COMMENT =
			{(byte) 0x81, 't', 'h', 'e', 'o', 'r', 'a', 0, 0, 0, 0, 0, 0, 0,
					0};
	private static final byte[] SPEEX_ID =
			{'S', 'p', 'e', 'e', 'x', ' ', ' ', ' '};
	private static final byte[] EMPTY_SPEEX_COMMENT = {0, 0, 0, 0, 0, 0, 0, 0};
	private static final byte[] FLAC_ID = {0x7F, 'F', 'L', 'A', 'C'};
	private static final byte[] FLAC_MAGIC = {'f', 'L', 'a', 'C'};
	private static final byte[] EMPTY_FLAC_COMMENT =
			{(byte) 0x84, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0};
	private static final byte[] SKELETON_ID =
			{'f', 'i', 's', 'h', 'e', 'a', 'd', 0};

	private static final int SPEEX_HEADER_LENGTH = 80;
	private static final int SPEEX_EXTRA_HEADERS = 68;
	private static final int FLAC_ID_LENGTH = 51;
	private static final int FLAC_STREAMINFO_LENGTH = 34;
	private static final int FLAC_INVALID_BLOCK = 127;

	private static final int[] CRC_TABLE = crcTable();

	private OggCommentStripper() {
	}

	static byte[] withoutComments(byte[] d) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
		Map<Long, Stream> streams = new HashMap<>();
		int pos = 0;
		while (pos < d.length) {
			if (!matches(d, pos, "OggS")) {
				if (AudioTagStripper.trailersStart(d, pos, d.length) != pos) {
					throw new IOException("malformed ogg stream");
				}
				break;
			}
			int length = pageLength(d, pos);
			if (checksum(d, pos, length) != u32le(d, pos + 22)) {
				throw new IOException("ogg page checksum mismatch");
			}
			page(d, pos, length, streams, out);
			pos += length;
		}
		boolean kept = false;
		for (Stream s : streams.values()) {
			if (s.collecting()) throw new IOException("ogg headers cut short");
			if (!s.dropped) kept = true;
		}
		if (!kept) throw new IOException("ogg file without a stream to send");
		return out.toByteArray();
	}

	private static void page(byte[] d, int pos, int length,
			Map<Long, Stream> streams, ByteArrayOutputStream out)
			throws IOException {
		int flags = d[pos + 5] & 0xFF;
		long serial = u32le(d, pos + 14);
		Stream s = streams.get(serial);
		if ((flags & FIRST) != 0) {
			if (s != null && !s.ended) {
				throw new IOException("ogg stream started twice");
			}
			s = start(d, pos, length, flags);
			streams.put(serial, s);
			if (!s.dropped) writeFirstPage(s, d, pos, length, out);
		} else if (s == null || s.ended) {
			throw new IOException("ogg page outside its stream");
		} else if (s.collecting()) {
			collect(s, d, pos, serial, out);
		} else if (!s.dropped) {
			copy(d, pos, length, s.shift, out);
		}
		if ((flags & LAST) != 0) {
			s.ended = true;
			if (s.collecting()) throw new IOException("ogg headers cut short");
		}
	}

	private static Stream start(byte[] d, int pos, int length, int flags)
			throws IOException {
		int segments = d[pos + 26] & 0xFF;
		int body = pos + HEADER + segments;
		int bodyLength = length - HEADER - segments;
		Stream s;
		if (startsWith(d, body, VORBIS_ID)) {
			s = new Stream(VORBIS, 2);
		} else if (startsWith(d, body, OPUS_ID)) {
			s = new Stream(OPUS, 1);
		} else if (startsWith(d, body, THEORA_ID)) {
			s = new Stream(THEORA, 2);
		} else if (startsWith(d, body, SPEEX_ID)) {
			s = speexStream(d, body, bodyLength);
		} else if (startsWith(d, body, FLAC_ID)) {
			s = flacStream(d, body, bodyLength);
		} else if (startsWith(d, body, SKELETON_ID)) {
			Stream skeleton = new Stream(COPY, 0);
			skeleton.dropped = true;
			return skeleton;
		} else {
			throw new IOException("ogg codec whose metadata cannot be removed");
		}
		if ((flags & CONTINUED) != 0 || segments == 0) {
			throw new IOException("malformed ogg first page");
		}
		for (int i = 0; i < segments; i++) {
			int lace = d[pos + HEADER + i] & 0xFF;
			if ((lace < 255) != (i == segments - 1)) {
				throw new IOException("malformed ogg first page");
			}
		}
		return s;
	}

	private static Stream speexStream(byte[] d, int body, int bodyLength)
			throws IOException {
		if (bodyLength < SPEEX_HEADER_LENGTH) {
			throw new IOException("malformed speex header");
		}
		if (u32le(d, body + SPEEX_EXTRA_HEADERS) != 0) {
			throw new IOException("speex extra headers");
		}
		return new Stream(SPEEX, 1);
	}

	private static Stream flacStream(byte[] d, int body, int bodyLength)
			throws IOException {
		if (bodyLength != FLAC_ID_LENGTH || d[body + 5] != 1
				|| !startsWith(d, body + 9, FLAC_MAGIC)
				|| (d[body + 13] & 0x7F) != 0
				|| u24(d, body + 14) != FLAC_STREAMINFO_LENGTH) {
			throw new IOException("malformed ogg flac header");
		}
		int count = ((d[body + 7] & 0xFF) << 8) | (d[body + 8] & 0xFF);
		boolean last = (d[body + 13] & 0x80) != 0;
		if (last != (count == 0)) {
			throw new IOException("ogg flac header count unknown");
		}
		return count == 0 ? new Stream(COPY, 0) : new Stream(FLAC, count);
	}

	private static void writeFirstPage(Stream s, byte[] d, int pos,
			int length, ByteArrayOutputStream out) {
		if (s.codec != FLAC) {
			out.write(d, pos, length);
			return;
		}
		byte[] page = new byte[length];
		System.arraycopy(d, pos, page, 0, length);
		int body = HEADER + (page[26] & 0xFF);
		page[body + 7] = 0;
		page[body + 8] = 1;
		putLe(page, 22, checksum(page, 0, length), 4);
		out.write(page, 0, length);
	}

	private static void collect(Stream s, byte[] d, int pos, long serial,
			ByteArrayOutputStream out) throws IOException {
		int flags = d[pos + 5] & 0xFF;
		long seq = u32le(d, pos + 18);
		if (((flags & CONTINUED) != 0) != s.inPacket) {
			throw new IOException("malformed ogg header page");
		}
		if (s.firstSeq < 0) s.firstSeq = seq;
		int segments = d[pos + 26] & 0xFF;
		int body = pos + HEADER + segments;
		for (int i = 0; i < segments; i++) {
			if (s.packets.size() == s.headers) {
				throw new IOException("ogg audio shares a header page");
			}
			int lace = d[pos + HEADER + i] & 0xFF;
			s.partial.write(d, body, lace);
			body += lace;
			s.inPacket = lace == 255;
			if (!s.inPacket) {
				s.packets.add(s.partial.toByteArray());
				s.partial.reset();
			}
		}
		if (s.packets.size() < s.headers) return;
		List<byte[]> cleaned = cleanHeaders(s.codec, s.packets);
		int written = writePackets(cleaned, serial, s.firstSeq,
				(flags & LAST) != 0, out);
		s.shift = seq - (s.firstSeq + written - 1);
		s.packets.clear();
		s.headers = 0;
	}

	private static List<byte[]> cleanHeaders(int codec, List<byte[]> h)
			throws IOException {
		List<byte[]> out = new ArrayList<>();
		switch (codec) {
			case VORBIS:
				requireMagic(h.get(0), VORBIS_COMMENT);
				requireMagic(h.get(1), VORBIS_SETUP);
				out.add(EMPTY_VORBIS_COMMENT);
				out.add(h.get(1));
				return out;
			case OPUS:
				requireMagic(h.get(0), OPUS_COMMENT);
				return Collections.singletonList(EMPTY_OPUS_COMMENT);
			case THEORA:
				requireMagic(h.get(0), THEORA_COMMENT);
				requireMagic(h.get(1), THEORA_SETUP);
				out.add(EMPTY_THEORA_COMMENT);
				out.add(h.get(1));
				return out;
			case SPEEX:
				return Collections.singletonList(EMPTY_SPEEX_COMMENT);
			case FLAC:
				for (int i = 0; i < h.size(); i++) {
					requireFlacBlock(h.get(i), i == h.size() - 1);
				}
				return Collections.singletonList(EMPTY_FLAC_COMMENT);
			default:
				throw new IOException("unknown ogg codec");
		}
	}

	private static void requireMagic(byte[] packet, byte[] magic)
			throws IOException {
		if (!startsWith(packet, 0, magic)) {
			throw new IOException("malformed ogg headers");
		}
	}

	private static void requireFlacBlock(byte[] p, boolean last)
			throws IOException {
		if (p.length < 4) throw new IOException("malformed flac block");
		int type = p[0] & 0x7F;
		if (type == 0 || type == FLAC_INVALID_BLOCK
				|| u24(p, 1) != p.length - 4
				|| ((p[0] & 0x80) != 0) != last) {
			throw new IOException("malformed flac block");
		}
	}

	private static int writePackets(List<byte[]> packets, long serial,
			long firstSeq, boolean last, ByteArrayOutputStream out) {
		List<int[]> segments = new ArrayList<>();
		for (int p = 0; p < packets.size(); p++) {
			int length = packets.get(p).length;
			for (int off = 0; ; off += 255) {
				int lace = Math.min(255, length - off);
				segments.add(new int[] {p, off, lace});
				if (lace < 255) break;
			}
		}
		int pages = 0;
		for (int from = 0; from < segments.size(); from += MAX_SEGMENTS) {
			int to = Math.min(segments.size(), from + MAX_SEGMENTS);
			boolean continued = from > 0 && segments.get(from - 1)[2] == 255;
			boolean ends = false;
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			byte[] lacing = new byte[to - from];
			for (int i = from; i < to; i++) {
				int[] seg = segments.get(i);
				lacing[i - from] = (byte) seg[2];
				body.write(packets.get(seg[0]), seg[1], seg[2]);
				if (seg[2] < 255) ends = true;
			}
			int flags = (continued ? CONTINUED : 0)
					| (last && to == segments.size() ? LAST : 0);
			byte[] page = new byte[HEADER + lacing.length + body.size()];
			page[0] = 'O';
			page[1] = 'g';
			page[2] = 'g';
			page[3] = 'S';
			page[5] = (byte) flags;
			putLe(page, 6, ends ? 0 : NO_GRANULE, 8);
			putLe(page, 14, serial, 4);
			putLe(page, 18, firstSeq + pages, 4);
			page[26] = (byte) lacing.length;
			System.arraycopy(lacing, 0, page, HEADER, lacing.length);
			byte[] b = body.toByteArray();
			System.arraycopy(b, 0, page, HEADER + lacing.length, b.length);
			putLe(page, 22, checksum(page, 0, page.length), 4);
			out.write(page, 0, page.length);
			pages++;
		}
		return pages;
	}

	private static void copy(byte[] d, int pos, int length, long shift,
			ByteArrayOutputStream out) {
		if (shift == 0) {
			out.write(d, pos, length);
			return;
		}
		byte[] page = new byte[length];
		System.arraycopy(d, pos, page, 0, length);
		putLe(page, 18, u32le(page, 18) - shift, 4);
		putLe(page, 22, checksum(page, 0, length), 4);
		out.write(page, 0, length);
	}

	private static int pageLength(byte[] d, int pos) throws IOException {
		if (d.length - pos < HEADER) {
			throw new IOException("truncated ogg page");
		}
		if (d[pos + 4] != 0) throw new IOException("unknown ogg version");
		int segments = d[pos + 26] & 0xFF;
		if (d.length - pos - HEADER < segments) {
			throw new IOException("truncated ogg page");
		}
		long length = HEADER + segments;
		for (int i = 0; i < segments; i++) {
			length += d[pos + HEADER + i] & 0xFF;
		}
		if (length > d.length - pos) {
			throw new IOException("truncated ogg page");
		}
		return (int) length;
	}

	private static long checksum(byte[] d, int pos, int length) {
		int crc = 0;
		for (int i = 0; i < length; i++) {
			int b = i >= 22 && i < 26 ? 0 : d[pos + i] & 0xFF;
			crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b) & 0xFF];
		}
		return crc & 0xFFFFFFFFL;
	}

	private static int[] crcTable() {
		int[] table = new int[256];
		for (int i = 0; i < 256; i++) {
			int r = i << 24;
			for (int k = 0; k < 8; k++) {
				r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
			}
			table[i] = r;
		}
		return table;
	}

	private static void putLe(byte[] d, int off, long v, int bytes) {
		for (int i = 0; i < bytes; i++) d[off + i] = (byte) (v >> (8 * i));
	}

	private static int u24(byte[] d, int off) {
		return ((d[off] & 0xFF) << 16) | ((d[off + 1] & 0xFF) << 8)
				| (d[off + 2] & 0xFF);
	}

	private static boolean startsWith(byte[] d, int off, byte[] prefix) {
		if (off < 0 || d.length - off < prefix.length) return false;
		for (int i = 0; i < prefix.length; i++) {
			if (d[off + i] != prefix[i]) return false;
		}
		return true;
	}

	private static final class Stream {

		private final int codec;
		private final List<byte[]> packets = new ArrayList<>();
		private final ByteArrayOutputStream partial =
				new ByteArrayOutputStream();
		private int headers;
		private boolean inPacket;
		private boolean ended;
		private boolean dropped;
		private long firstSeq = -1;
		private long shift;

		private Stream(int codec, int headers) {
			this.codec = codec;
			this.headers = headers;
		}

		private boolean collecting() {
			return headers > 0;
		}
	}
}

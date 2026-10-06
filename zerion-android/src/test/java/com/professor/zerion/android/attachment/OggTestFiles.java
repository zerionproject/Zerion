package com.professor.zerion.android.attachment;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class OggTestFiles {

	static final int CONTINUED = 1;
	static final int FIRST = 2;
	static final int LAST = 4;

	static final byte[] EMPTY_OPUS_TAGS = concat(ascii("OpusTags"),
			new byte[8]);
	static final byte[] EMPTY_VORBIS_COMMENT = concat(
			new byte[] {3, 'v', 'o', 'r', 'b', 'i', 's'}, new byte[8],
			new byte[] {1});

	private OggTestFiles() {
	}

	static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	static byte[] le32(long v) {
		return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16),
				(byte) (v >> 24)};
	}

	static byte[] commentList(String vendor, String... comments) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] v = vendor.getBytes(StandardCharsets.UTF_8);
		out.write(le32(v.length), 0, 4);
		out.write(v, 0, v.length);
		out.write(le32(comments.length), 0, 4);
		for (String c : comments) {
			byte[] b = c.getBytes(StandardCharsets.UTF_8);
			out.write(le32(b.length), 0, 4);
			out.write(b, 0, b.length);
		}
		return out.toByteArray();
	}

	static byte[] opusHead() {
		return concat(ascii("OpusHead"), new byte[] {1, 2, 0x38, 1,
				(byte) 0x80, (byte) 0xBB, 0, 0, 0, 0, 0});
	}

	static byte[] opusTags(String... comments) {
		return concat(ascii("OpusTags"), commentList("libopus 1.3.1",
				comments));
	}

	static byte[] vorbisId() {
		byte[] id = new byte[30];
		byte[] magic = {1, 'v', 'o', 'r', 'b', 'i', 's'};
		System.arraycopy(magic, 0, id, 0, magic.length);
		id[11] = 2;
		id[12] = (byte) 0x44;
		id[13] = (byte) 0xAC;
		id[28] = (byte) 0xB8;
		id[29] = 1;
		return id;
	}

	static byte[] vorbisComment(String... comments) {
		return concat(new byte[] {3, 'v', 'o', 'r', 'b', 'i', 's'},
				commentList("Xiph.Org libVorbis I 20200704", comments),
				new byte[] {1});
	}

	static byte[] vorbisSetup(int length) {
		byte[] s = new byte[length];
		byte[] magic = {5, 'v', 'o', 'r', 'b', 'i', 's'};
		System.arraycopy(magic, 0, s, 0, magic.length);
		for (int i = magic.length; i < length; i++) s[i] = (byte) (i * 11);
		return s;
	}

	static List<byte[]> audioPackets(int count, int seed) {
		List<byte[]> packets = new ArrayList<>();
		for (int p = 0; p < count; p++) {
			byte[] b = new byte[40 + (p * 37 + seed) % 300];
			for (int i = 0; i < b.length; i++) {
				b[i] = (byte) (i * 7 + p * 13 + seed);
			}
			packets.add(b);
		}
		return packets;
	}

	static byte[] page(int flags, long granule, long serial, long seq,
			byte[] lacing, byte[] body) {
		byte[] p = new byte[27 + lacing.length + body.length];
		p[0] = 'O';
		p[1] = 'g';
		p[2] = 'g';
		p[3] = 'S';
		p[5] = (byte) flags;
		for (int i = 0; i < 8; i++) p[6 + i] = (byte) (granule >> (8 * i));
		for (int i = 0; i < 4; i++) p[14 + i] = (byte) (serial >> (8 * i));
		for (int i = 0; i < 4; i++) p[18 + i] = (byte) (seq >> (8 * i));
		p[26] = (byte) lacing.length;
		System.arraycopy(lacing, 0, p, 27, lacing.length);
		System.arraycopy(body, 0, p, 27 + lacing.length, body.length);
		long crc = crc(p);
		for (int i = 0; i < 4; i++) p[22 + i] = (byte) (crc >> (8 * i));
		return p;
	}

	static long crc(byte[] page) {
		long crc = 0;
		for (int i = 0; i < page.length; i++) {
			int b = i >= 22 && i < 26 ? 0 : page[i] & 0xFF;
			crc ^= (long) b << 24;
			for (int k = 0; k < 8; k++) {
				crc = (crc & 0x80000000L) != 0
						? ((crc << 1) ^ 0x04C11DB7L) & 0xFFFFFFFFL
						: (crc << 1) & 0xFFFFFFFFL;
			}
		}
		return crc;
	}

	static long pages(ByteArrayOutputStream out, List<byte[]> packets,
			long serial, long seq, int firstFlags, long[] granules,
			boolean lastPage, int maxSegments) {
		List<int[]> segs = new ArrayList<>();
		for (int p = 0; p < packets.size(); p++) {
			int len = packets.get(p).length;
			for (int off = 0; ; off += 255) {
				int lace = Math.min(255, len - off);
				segs.add(new int[] {p, off, lace});
				if (lace < 255) break;
			}
		}
		boolean first = true;
		for (int from = 0; from < segs.size(); from += maxSegments) {
			int to = Math.min(segs.size(), from + maxSegments);
			byte[] lacing = new byte[to - from];
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			long granule = -1;
			for (int i = from; i < to; i++) {
				int[] s = segs.get(i);
				lacing[i - from] = (byte) s[2];
				body.write(packets.get(s[0]), s[1], s[2]);
				if (s[2] < 255) granule = granules[s[0]];
			}
			int flags = first ? firstFlags : 0;
			if (from > 0 && segs.get(from - 1)[2] == 255) flags |= CONTINUED;
			if (lastPage && to == segs.size()) flags |= LAST;
			byte[] pg = page(flags, granule, serial, seq++, lacing,
					body.toByteArray());
			out.write(pg, 0, pg.length);
			first = false;
		}
		return seq;
	}

	static long[] zeros(int n) {
		return new long[n];
	}

	static long[] granules(int n, long step) {
		long[] g = new long[n];
		for (int i = 0; i < n; i++) g[i] = (i + 1) * step;
		return g;
	}

	static byte[] opus(long serial, byte[] tags, List<byte[]> audio,
			int headerSegments, int audioSegments) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		long seq = pages(out, list(opusHead()), serial, 0, FIRST, zeros(1),
				false, 255);
		seq = pages(out, list(tags), serial, seq, 0, zeros(1), false,
				headerSegments);
		pages(out, audio, serial, seq, 0, granules(audio.size(), 960), true,
				audioSegments);
		return out.toByteArray();
	}

	static byte[] vorbis(long serial, byte[] comment, byte[] setup,
			List<byte[]> audio, int headerSegments, int audioSegments) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		long seq = pages(out, list(vorbisId()), serial, 0, FIRST, zeros(1),
				false, 255);
		seq = pages(out, list(comment, setup), serial, seq, 0, zeros(2),
				false, headerSegments);
		pages(out, audio, serial, seq, 0, granules(audio.size(), 1024),
				true, audioSegments);
		return out.toByteArray();
	}

	static List<byte[]> list(byte[]... packets) {
		List<byte[]> l = new ArrayList<>();
		for (byte[] p : packets) l.add(p);
		return l;
	}

	static final class Page {
		int flags;
		long granule;
		long serial;
		long seq;
		boolean checksumValid;
		byte[] lacing;
		byte[] body;
	}

	static List<Page> readPages(byte[] d) {
		List<Page> pages = new ArrayList<>();
		int pos = 0;
		while (pos < d.length) {
			if (d[pos] != 'O' || d[pos + 1] != 'g' || d[pos + 2] != 'g'
					|| d[pos + 3] != 'S') {
				throw new AssertionError("no page at " + pos);
			}
			Page p = new Page();
			p.flags = d[pos + 5] & 0xFF;
			p.granule = 0;
			for (int i = 7; i >= 0; i--) {
				p.granule = (p.granule << 8) | (d[pos + 6 + i] & 0xFF);
			}
			p.serial = u32(d, pos + 14);
			p.seq = u32(d, pos + 18);
			int n = d[pos + 26] & 0xFF;
			p.lacing = new byte[n];
			System.arraycopy(d, pos + 27, p.lacing, 0, n);
			int len = 0;
			for (byte b : p.lacing) len += b & 0xFF;
			p.body = new byte[len];
			System.arraycopy(d, pos + 27 + n, p.body, 0, len);
			byte[] whole = new byte[27 + n + len];
			System.arraycopy(d, pos, whole, 0, whole.length);
			p.checksumValid = crc(whole) == u32(d, pos + 22);
			pages.add(p);
			pos += whole.length;
		}
		return pages;
	}

	static List<byte[]> packets(List<Page> pages, long serial) {
		List<byte[]> packets = new ArrayList<>();
		ByteArrayOutputStream partial = new ByteArrayOutputStream();
		for (Page p : pages) {
			if (p.serial != serial) continue;
			int off = 0;
			for (byte l : p.lacing) {
				int lace = l & 0xFF;
				partial.write(p.body, off, lace);
				off += lace;
				if (lace < 255) {
					packets.add(partial.toByteArray());
					partial.reset();
				}
			}
		}
		return packets;
	}

	static long u32(byte[] d, int off) {
		return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8)
				| ((d[off + 2] & 0xFFL) << 16) | ((d[off + 3] & 0xFFL) << 24);
	}
}

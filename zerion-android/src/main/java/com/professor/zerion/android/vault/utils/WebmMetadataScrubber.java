package com.professor.zerion.android.vault.utils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

@NotNullByDefault
public final class WebmMetadataScrubber {

	static final long EBML = 0x1A45DFA3L;
	static final long SEGMENT = 0x18538067L;
	static final long SEEK_HEAD = 0x114D9B74L;
	static final long INFO = 0x1549A966L;
	static final long TRACKS = 0x1654AE6BL;
	static final long CLUSTER = 0x1F43B675L;
	static final long CUES = 0x1C53BB6BL;
	static final long TAGS = 0x1254C367L;
	static final long ATTACHMENTS = 0x1941A469L;
	static final long CHAPTERS = 0x1043A770L;
	static final long VOID = 0xECL;
	static final long CRC32 = 0xBFL;
	static final long MUXING_APP = 0x4D80L;
	static final long WRITING_APP = 0x5741L;
	static final long TITLE = 0x7BA9L;
	static final long DATE_UTC = 0x4461L;
	static final long SEGMENT_FILENAME = 0x7384L;
	static final long PREV_FILENAME = 0x3C83ABL;
	static final long NEXT_FILENAME = 0x3E83BBL;
	static final long TRACK_ENTRY = 0xAEL;
	static final long NAME = 0x536EL;
	static final long CODEC_ID = 0x86L;
	static final long CODEC_PRIVATE = 0x63A2L;

	private static final long[] BLANKED = {MUXING_APP, WRITING_APP, TITLE,
			DATE_UTC, SEGMENT_FILENAME, PREV_FILENAME, NEXT_FILENAME};

	private static final long[] REFUSED = {TAGS, ATTACHMENTS, CHAPTERS};

	private static final long[] SEGMENT_LEVEL = {SEEK_HEAD, INFO, TRACKS,
			CLUSTER, CUES, TAGS, ATTACHMENTS, CHAPTERS};

	private static final int MAX_CHILDREN = 4096;
	private static final int MAX_SEGMENT_CHILDREN = 1 << 20;
	private static final int MAX_CODEC_ID = 64;
	private static final int MAX_CODEC_PRIVATE = 1024 * 1024;
	private static final int CHUNK = 64 * 1024;

	private static final byte[] WRITER =
			"android".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] VORBIS_COMMENT =
			{3, 'v', 'o', 'r', 'b', 'i', 's'};

	private WebmMetadataScrubber() {
	}

	public static void scrub(File file) throws IOException {
		try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
			long end = f.length();
			Element head = element(f, 0, end);
			if (head.id != EBML || head.unknown) {
				throw new IOException("not an EBML file");
			}
			requireNoWriter(f, head.start, head.end);
			long pos = head.end;
			boolean segment = false;
			int count = 0;
			while (pos < end) {
				if (++count > MAX_SEGMENT_CHILDREN) {
					throw new IOException("too many elements");
				}
				Element e = element(f, pos, end);
				if (e.id == SEGMENT && !segment) {
					segment = true;
					scrubSegment(f, e);
					pos = e.end;
				} else {
					pos = scrubChild(f, e);
				}
			}
			if (!segment) throw new IOException("no segment");
		}
	}

	private static void scrubSegment(RandomAccessFile f, Element segment)
			throws IOException {
		long pos = segment.data;
		int count = 0;
		while (pos < segment.end) {
			if (++count > MAX_SEGMENT_CHILDREN) {
				throw new IOException("too many elements");
			}
			pos = scrubChild(f, element(f, pos, segment.end));
		}
	}

	private static long scrubChild(RandomAccessFile f, Element e)
			throws IOException {
		if (is(e.id, REFUSED)) {
			throw new IOException("tags, attachments or chapters");
		}
		if (e.id == CLUSTER) return e.unknown ? clusterEnd(f, e) : e.end;
		if (e.unknown || e.id == EBML || e.id == SEGMENT) {
			throw new IOException("unexpected element");
		}
		if (e.id == INFO) {
			scrubInfo(f, e);
		} else if (e.id == TRACKS) {
			scrubTracks(f, e);
		}
		requireNoWriter(f, e.start, e.end);
		return e.end;
	}

	private static long clusterEnd(RandomAccessFile f, Element cluster)
			throws IOException {
		long pos = cluster.data;
		int count = 0;
		while (pos < cluster.end) {
			if (++count > MAX_SEGMENT_CHILDREN) {
				throw new IOException("too many elements");
			}
			Element e = element(f, pos, cluster.end);
			if (is(e.id, SEGMENT_LEVEL)) return pos;
			if (e.unknown) throw new IOException("element of unknown size");
			pos = e.end;
		}
		return pos;
	}

	private static void scrubInfo(RandomAccessFile f, Element info)
			throws IOException {
		long pos = info.data;
		int count = 0;
		while (pos < info.end) {
			if (++count > MAX_CHILDREN) {
				throw new IOException("too many elements");
			}
			Element e = child(f, pos, info.end);
			if (is(e.id, BLANKED)) zero(f, e.data, e.end);
			pos = e.end;
		}
	}

	private static void scrubTracks(RandomAccessFile f, Element tracks)
			throws IOException {
		long pos = tracks.data;
		int count = 0;
		while (pos < tracks.end) {
			if (++count > MAX_CHILDREN) {
				throw new IOException("too many elements");
			}
			Element e = child(f, pos, tracks.end);
			if (e.id == TRACK_ENTRY) scrubTrack(f, e);
			pos = e.end;
		}
	}

	private static void scrubTrack(RandomAccessFile f, Element track)
			throws IOException {
		long pos = track.data;
		int count = 0;
		String codec = "";
		Element codecPrivate = null;
		while (pos < track.end) {
			if (++count > MAX_CHILDREN) {
				throw new IOException("too many elements");
			}
			Element e = child(f, pos, track.end);
			if (e.id == NAME) {
				zero(f, e.data, e.end);
			} else if (e.id == CODEC_ID) {
				codec = new String(read(f, e, MAX_CODEC_ID),
						StandardCharsets.US_ASCII).trim();
			} else if (e.id == CODEC_PRIVATE) {
				codecPrivate = e;
			}
			pos = e.end;
		}
		if (codecPrivate != null && codec.startsWith("A_VORBIS")) {
			byte[] p = read(f, codecPrivate, MAX_CODEC_PRIVATE);
			blankVorbisComment(p);
			f.seek(codecPrivate.data);
			f.write(p);
		}
	}

	static void blankVorbisComment(byte[] p) throws IOException {
		if (p.length < 1 || p[0] != 2) {
			throw new IOException("vorbis headers not laced");
		}
		int pos = 1;
		long[] sizes = new long[2];
		for (int i = 0; i < 2; i++) {
			while (true) {
				if (pos >= p.length) {
					throw new IOException("vorbis headers cut short");
				}
				int lace = p[pos++] & 0xFF;
				sizes[i] += lace;
				if (lace < 255) break;
			}
		}
		long comment = pos + sizes[0];
		long end = comment + sizes[1];
		if (end > p.length) throw new IOException("vorbis headers cut short");
		int at = (int) comment;
		int stop = (int) end;
		for (int i = 0; i < VORBIS_COMMENT.length; i++) {
			if (at + i >= stop || p[at + i] != VORBIS_COMMENT[i]) {
				throw new IOException("no vorbis comment header");
			}
		}
		at = blankString(p, at + VORBIS_COMMENT.length, stop);
		long comments = u32le(p, at, stop);
		at += 4;
		for (long i = 0; i < comments; i++) at = blankString(p, at, stop);
		if (at >= stop || (p[at] & 1) == 0) {
			throw new IOException("vorbis comment header not framed");
		}
	}

	private static int blankString(byte[] p, int at, int stop)
			throws IOException {
		long length = u32le(p, at, stop);
		long from = at + 4L;
		if (length > stop - from) {
			throw new IOException("vorbis comment cut short");
		}
		for (int i = (int) from; i < from + length; i++) p[i] = 0;
		return (int) (from + length);
	}

	private static long u32le(byte[] p, int at, int stop) throws IOException {
		if (stop - at < 4) throw new IOException("vorbis comment cut short");
		return (p[at] & 0xFFL) | ((p[at + 1] & 0xFFL) << 8)
				| ((p[at + 2] & 0xFFL) << 16) | ((p[at + 3] & 0xFFL) << 24);
	}

	private static Element child(RandomAccessFile f, long pos, long limit)
			throws IOException {
		Element e = element(f, pos, limit);
		if (e.unknown) throw new IOException("element of unknown size");
		if (e.id == CRC32) throw new IOException("checksummed element");
		return e;
	}

	static Element element(RandomAccessFile f, long pos, long limit)
			throws IOException {
		if (limit - pos < 2) throw new IOException("truncated element");
		byte[] h = new byte[12];
		int available = (int) Math.min(h.length, limit - pos);
		f.seek(pos);
		f.readFully(h, 0, available);
		int idLength = vintLength(h[0] & 0xFF, 4);
		if (idLength == 0 || idLength >= available) {
			throw new IOException("malformed element id");
		}
		long id = 0;
		for (int i = 0; i < idLength; i++) id = (id << 8) | (h[i] & 0xFF);
		int first = h[idLength] & 0xFF;
		int sizeLength = vintLength(first, 8);
		if (sizeLength == 0 || idLength + sizeLength > available) {
			throw new IOException("malformed element size");
		}
		int mask = 0xFF >> sizeLength;
		long size = first & mask;
		boolean unknown = size == mask;
		for (int i = 1; i < sizeLength; i++) {
			int b = h[idLength + i] & 0xFF;
			size = (size << 8) | b;
			if (b != 0xFF) unknown = false;
		}
		long data = pos + idLength + sizeLength;
		if (unknown) return new Element(id, pos, data, limit, true);
		if (size > limit - data) {
			throw new IOException("element runs past its parent");
		}
		return new Element(id, pos, data, data + size, false);
	}

	private static int vintLength(int first, int max) {
		for (int i = 0; i < max; i++) {
			if ((first & (0x80 >> i)) != 0) return i + 1;
		}
		return 0;
	}

	private static boolean is(long id, long[] ids) {
		for (long i : ids) {
			if (i == id) return true;
		}
		return false;
	}

	private static byte[] read(RandomAccessFile f, Element e, int max)
			throws IOException {
		long length = e.end - e.data;
		if (length > max) throw new IOException("element too large");
		byte[] b = new byte[(int) length];
		f.seek(e.data);
		f.readFully(b);
		return b;
	}

	private static void zero(RandomAccessFile f, long from, long to)
			throws IOException {
		if (to <= from) return;
		byte[] zeros = new byte[(int) Math.min(CHUNK, to - from)];
		long pos = from;
		f.seek(pos);
		while (pos < to) {
			int n = (int) Math.min(zeros.length, to - pos);
			f.write(zeros, 0, n);
			pos += n;
		}
	}

	private static void requireNoWriter(RandomAccessFile f, long start,
			long end) throws IOException {
		byte[] buf = new byte[CHUNK + WRITER.length];
		long pos = start;
		int carry = 0;
		while (pos < end) {
			int n = (int) Math.min(CHUNK, end - pos);
			f.seek(pos);
			f.readFully(buf, carry, n);
			int length = carry + n;
			if (containsIgnoringCase(buf, length)) {
				throw new IOException("writer details left in the file");
			}
			carry = Math.min(WRITER.length - 1, length);
			System.arraycopy(buf, length - carry, buf, 0, carry);
			pos += n;
		}
	}

	private static boolean containsIgnoringCase(byte[] hay, int length) {
		outer:
		for (int i = 0; i + WRITER.length <= length; i++) {
			for (int j = 0; j < WRITER.length; j++) {
				int c = hay[i + j];
				if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
				if (c != WRITER[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	static final class Element {

		final long id;
		final long start;
		final long data;
		final long end;
		final boolean unknown;

		private Element(long id, long start, long data, long end,
				boolean unknown) {
			this.id = id;
			this.start = start;
			this.data = data;
			this.end = end;
			this.unknown = unknown;
		}
	}
}

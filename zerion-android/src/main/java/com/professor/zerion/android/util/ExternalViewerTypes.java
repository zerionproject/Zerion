package com.professor.zerion.android.util;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@NotNullByDefault
public final class ExternalViewerTypes {

	public static final String OPAQUE = "application/octet-stream";
	public static final String TEXT = "text/plain";

	static final int HEAD_BYTES = 4096;

	private static final int MAX_DEPTH_BOXES = 256;

	private static final int MAX_BOXES = 1024;

	private static final int MAX_LEADING_TAGS = 16;

	private static final int MIN_GUESSED_TEXT = 16;

	private static final Pattern WELL_FORMED = Pattern.compile(
			"[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}");

	private static final Pattern URL_START = Pattern.compile(
			"^[a-z][a-z0-9+.-]{0,31}://.*", Pattern.DOTALL);

	private static final Pattern BENCODED_DICTIONARY = Pattern.compile(
			"^d[0-9]{1,6}:.*", Pattern.DOTALL);

	private static final Pattern HEADER_FIELD = Pattern.compile(
			"^[a-z][a-z0-9-]{0,63}:");

	private static final Set<String> ACTIVE = new HashSet<>(Arrays.asList(
			"text/html",
			"application/xhtml+xml",
			"image/svg+xml",
			"image/svg",
			"image/svg-xml",
			"text/xml",
			"application/xml",
			"text/javascript",
			"application/javascript",
			"application/x-javascript",
			"application/ecmascript",
			"text/ecmascript",
			"text/css",
			"message/rfc822",
			"multipart/related",
			"text/uri-list",
			"application/x-url",
			"application/internet-shortcut",
			"text/x-uri",
			"application/x-shockwave-flash",
			"application/pdf+xml",
			"text/calendar",
			"text/x-vcalendar",
			"text/vcard",
			"text/x-vcard",
			"audio/x-scpls",
			"audio/scpls",
			"application/pls",
			"video/x-ms-asf",
			"video/x-ms-asx",
			"audio/x-ms-asx",
			"audio/x-ms-wax",
			"video/x-ms-wvx",
			"video/x-ms-wmx",
			"application/vnd.ms-asf",
			"application/vnd.ms-wpl",
			"application/sdp",
			"application/x-sdp",
			"application/smil",
			"application/x-smil",
			"video/vnd.mpeg.dash.mpd",
			"application/dash+xml",
			"application/x-bittorrent",
			"application/x-mimearchive",
			"application/epub+zip",
			"audio/x-pn-realaudio",
			"audio/x-pn-realaudio-plugin",
			"application/ram",
			"application/x-quicktime-media-link"));

	private static final String[] ACTIVE_PARTS = {"mpegurl", "m3u", "html",
			"script", "svg", "smil", "scpls", "bittorrent", "mimearchive",
			"webarchive", "xspf", "realaudio", "realmedia", "ms-asx",
			"ms-wax", "ms-wvx", "ms-wmx", "ms-wpl", "sdp"};

	private static final String[] MARKUP_TEXT_STARTS = {"#ext", "rtsptext",
			"v=0\n", "v=0\r", "mime-version:", "content-type:",
			"content-location:", "from:", "begin:v", "subject:", "date:",
			"message-id:", "received:", "return-path:", "reply-to:",
			"sender:", "to:", "snapshot-content-location:"};

	private static final String[] MESSAGE_HEADERS = {"mime-version:",
			"content-type:"};

	private ExternalViewerTypes() {
	}

	public static String effectiveType(@Nullable String declared) {
		if (declared == null) return OPAQUE;
		String m = declared.trim().toLowerCase(Locale.ROOT);
		int semi = m.indexOf(';');
		if (semi >= 0) m = m.substring(0, semi).trim();
		if (!WELL_FORMED.matcher(m).matches()) return OPAQUE;
		if (isActive(m)) return TEXT;
		return m;
	}

	@Nullable
	public static String typeForContent(@Nullable String declared,
			byte[] content) {
		try {
			return typeFor(declared, new ArraySource(content));
		} catch (IOException e) {
			return null;
		}
	}

	@Nullable
	public static String typeForFile(@Nullable String declared, File staged) {
		try (RandomAccessFile f = new RandomAccessFile(staged, "r")) {
			return typeFor(declared, new FileSource(f));
		} catch (IOException e) {
			return null;
		}
	}

	static boolean isActive(String m) {
		if (ACTIVE.contains(m)) return true;
		if (m.startsWith("text/") || m.startsWith("message/")
				|| m.startsWith("multipart/")) {
			return true;
		}
		if (m.endsWith("+xml")) return true;
		for (String part : ACTIVE_PARTS) {
			if (m.contains(part)) return true;
		}
		return false;
	}

	@Nullable
	private static String typeFor(@Nullable String declared, Source source)
			throws IOException {
		String type = effectiveType(declared);
		if (TEXT.equals(type)) return type;
		byte[] head = source.head(HEAD_BYTES);
		if (readsAsActiveText(head)) return null;
		long start = afterLeadingTags(source);
		if (start < 0) return null;
		Source rest = start == 0 ? source : new Slice(source, start);
		byte[] body = start == 0 ? head : rest.head(HEAD_BYTES);
		if (start > 0 && readsAsActiveText(body)) return null;
		if (type.startsWith("image/") && !MediaMagic.isStillImage(head)) {
			return null;
		}
		if (type.startsWith("video/") && !MediaMagic.isVideo(head)) {
			return null;
		}
		if (type.startsWith("audio/") && !MediaMagic.isAudio(body)
				&& !isFrameAfterPadding(body)) {
			return null;
		}
		if (MediaMagic.isIsoMedia(head) && loadsOtherMovies(source)) {
			return null;
		}
		if (start > 0 && MediaMagic.isIsoMedia(body)
				&& loadsOtherMovies(rest)) {
			return null;
		}
		return type;
	}

	static long afterLeadingTags(Source source) throws IOException {
		long end = source.length();
		long pos = 0;
		for (int i = 0; i <= MAX_LEADING_TAGS; i++) {
			long tag = leadingTagLength(source.read(pos, 32), end - pos);
			if (tag == 0) return pos;
			if (tag < 0 || i == MAX_LEADING_TAGS) return -1;
			pos += tag;
		}
		return -1;
	}

	private static long leadingTagLength(byte[] h, long remaining) {
		long total;
		if (MediaMagic.ascii(h, 0, "ID3")) {
			if (h.length < 10) return -1;
			int major = h[3] & 0xFF;
			int flags = h[5] & 0xFF;
			if (major < 2 || major > 4 || (h[4] & 0xFF) == 0xFF) return -1;
			int defined = major == 2 ? 0xC0 : major == 3 ? 0xE0 : 0xF0;
			if ((flags & ~defined) != 0) return -1;
			long size = 0;
			for (int i = 6; i < 10; i++) {
				if ((h[i] & 0x80) != 0) return -1;
				size = (size << 7) | (h[i] & 0x7F);
			}
			total = 10 + size + ((flags & 0x10) != 0 ? 10 : 0);
		} else if (MediaMagic.ascii(h, 0, "APETAGEX")) {
			if (h.length < 32) return -1;
			long flags = u32le(h, 20);
			if ((flags & 0x20000000L) == 0) return -1;
			total = 32 + u32le(h, 12);
		} else {
			return 0;
		}
		return total > remaining ? -1 : total;
	}

	private static boolean isFrameAfterPadding(byte[] body) {
		int i = 0;
		while (i < body.length && body[i] == 0) i++;
		return i > 0 && MediaMagic.isMpegAudioFrame(body, i);
	}

	static boolean readsAsActiveText(byte[] head) {
		if (isActiveText(leadingText(head))) return true;
		if (!hasByteOrderMark(head)) {
			if (isActiveText(unmarkedUtf16(head, true))) return true;
			if (isActiveText(unmarkedUtf16(head, false))) return true;
		}
		if (MediaMagic.isIsoMedia(head)) return false;
		byte[] rest = afterSkippableBytes(head);
		if (rest.length == head.length || rest.length == 0) return false;
		return isActiveText(singleByteText(rest))
				|| isActiveText(unmarkedUtf16(rest, true))
				|| isActiveText(unmarkedUtf16(rest, false));
	}

	private static byte[] afterSkippableBytes(byte[] head) {
		int i = 0;
		while (i < head.length) {
			int b = head[i] & 0xFF;
			if (b == 0 || isSpace(b)) {
				i++;
			} else if (b == 0xEF && i + 2 < head.length
					&& (head[i + 1] & 0xFF) == 0xBB
					&& (head[i + 2] & 0xFF) == 0xBF) {
				i += 3;
			} else if (i + 1 < head.length
					&& ((b == 0xFE && (head[i + 1] & 0xFF) == 0xFF)
					|| (b == 0xFF && (head[i + 1] & 0xFF) == 0xFE))) {
				i += 2;
			} else {
				break;
			}
		}
		return java.util.Arrays.copyOfRange(head, i, head.length);
	}

	private static String singleByteText(byte[] head) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		for (; i < head.length; i++) {
			int b = head[i] & 0xFF;
			if (!isText(b)) break;
			sb.append((char) b);
		}
		return guessedText(sb, i >= head.length);
	}

	private static boolean isActiveText(String s) {
		if (s.isEmpty()) return false;
		char c = s.charAt(0);
		if (c == '<') return true;
		if (c == '[' && s.length() > 1 && Character.isLetter(s.charAt(1))) {
			return true;
		}
		for (String start : MARKUP_TEXT_STARTS) {
			if (s.startsWith(start)) return true;
		}
		if (URL_START.matcher(s).matches()
				|| BENCODED_DICTIONARY.matcher(s).matches()) {
			return true;
		}
		if (c == '#' && URL_START.matcher(afterCommentLines(s)).matches()) {
			return true;
		}
		return readsAsMessageHeaders(s);
	}

	private static String afterCommentLines(String s) {
		int pos = 0;
		while (pos < s.length()) {
			char c = s.charAt(pos);
			if (c == '#') {
				int nl = s.indexOf('\n', pos);
				if (nl < 0) return "";
				pos = nl + 1;
			} else if (isSpace(c)) {
				pos++;
			} else {
				break;
			}
		}
		return s.substring(pos);
	}

	private static boolean readsAsMessageHeaders(String s) {
		if (!HEADER_FIELD.matcher(s).lookingAt()) return false;
		for (String line : s.split("\n")) {
			for (String header : MESSAGE_HEADERS) {
				if (line.startsWith(header)) return true;
			}
		}
		return false;
	}

	private static boolean hasByteOrderMark(byte[] head) {
		if (head.length >= 3 && (head[0] & 0xFF) == 0xEF
				&& (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
			return true;
		}
		return head.length >= 2 && (((head[0] & 0xFF) == 0xFE
				&& (head[1] & 0xFF) == 0xFF) || ((head[0] & 0xFF) == 0xFF
				&& (head[1] & 0xFF) == 0xFE));
	}

	private static String leadingText(byte[] head) {
		int start = 0;
		int step = 1;
		if (head.length >= 3 && (head[0] & 0xFF) == 0xEF
				&& (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
			start = 3;
		} else if (head.length >= 2 && (head[0] & 0xFF) == 0xFE
				&& (head[1] & 0xFF) == 0xFF) {
			start = 3;
			step = 2;
		} else if (head.length >= 2 && (head[0] & 0xFF) == 0xFF
				&& (head[1] & 0xFF) == 0xFE) {
			start = 2;
			step = 2;
		}
		StringBuilder sb = new StringBuilder();
		boolean leading = true;
		for (int i = start; i < head.length && sb.length() < HEAD_BYTES;
				i += step) {
			int b = head[i] & 0xFF;
			if (leading && isSpace(b)) continue;
			leading = false;
			sb.append((char) b);
		}
		return sb.toString().toLowerCase(Locale.ROOT);
	}

	private static String unmarkedUtf16(byte[] head, boolean bigEndian) {
		StringBuilder sb = new StringBuilder();
		boolean leading = true;
		int i = 0;
		for (; i + 1 < head.length; i += 2) {
			int high = head[bigEndian ? i : i + 1] & 0xFF;
			int low = head[bigEndian ? i + 1 : i] & 0xFF;
			if (high != 0) break;
			if (leading && (low == 0 || isSpace(low))) continue;
			leading = false;
			if (!isText(low)) break;
			sb.append((char) low);
		}
		return guessedText(sb, i + 1 >= head.length);
	}

	private static String guessedText(StringBuilder sb, boolean toTheEnd) {
		if (sb.length() < MIN_GUESSED_TEXT && !(toTheEnd && sb.length() > 0)) {
			return "";
		}
		return sb.toString().toLowerCase(Locale.ROOT);
	}

	private static boolean isSpace(int b) {
		return b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == 0x0B
				|| b == 0x0C;
	}

	private static boolean isText(int b) {
		return (b >= 0x20 && b < 0x7F) || isSpace(b);
	}

	static boolean loadsOtherMovies(Source source) throws IOException {
		long end = source.length();
		long pos = 0;
		for (int i = 0; i < MAX_BOXES; i++) {
			if (end - pos < 8) return false;
			byte[] h = source.read(pos, 16);
			long[] box = box(h, end - pos);
			if (box == null) return true;
			if (MediaMagic.ascii(h, 4, "moov")) {
				return movieLoadsOthers(source, pos + box[1],
						pos + Math.min(box[0], end - pos));
			}
			if (box[0] >= end - pos) return false;
			pos += box[0];
		}
		return true;
	}

	private static boolean movieLoadsOthers(Source source, long start,
			long end) throws IOException {
		long pos = start;
		for (int i = 0; i < MAX_BOXES; i++) {
			if (end - pos < 8) return false;
			byte[] h = source.read(pos, 16);
			long[] box = box(h, end - pos);
			if (box == null) return true;
			if (MediaMagic.ascii(h, 4, "rmra")
					|| MediaMagic.ascii(h, 4, "cmov")) {
				return true;
			}
			if (MediaMagic.ascii(h, 4, "trak") && trackLoadsOthers(source,
					pos + box[1], pos + Math.min(box[0], end - pos))) {
				return true;
			}
			if (box[0] >= end - pos) return false;
			pos += box[0];
		}
		return true;
	}

	private static boolean trackLoadsOthers(Source source, long start,
			long end) throws IOException {
		long[] bounds = {start, end};
		for (String type : new String[] {"mdia", "minf", "dinf", "dref"}) {
			bounds = child(source, bounds[0], bounds[1], type);
			if (bounds == null) return true;
			if (bounds == NOT_FOUND) return false;
		}
		return dataReferencesLeaveTheFile(source, bounds[0], bounds[1]);
	}

	private static final long[] NOT_FOUND = new long[0];

	@Nullable
	private static long[] child(Source source, long start, long end,
			String type) throws IOException {
		long pos = start;
		for (int i = 0; i < MAX_DEPTH_BOXES; i++) {
			if (end - pos < 8) return NOT_FOUND;
			byte[] h = source.read(pos, 16);
			long[] box = box(h, end - pos);
			if (box == null) return null;
			if (MediaMagic.ascii(h, 4, type)) {
				return new long[] {pos + box[1],
						pos + Math.min(box[0], end - pos)};
			}
			if (box[0] >= end - pos) return NOT_FOUND;
			pos += box[0];
		}
		return null;
	}

	private static boolean dataReferencesLeaveTheFile(Source source,
			long start, long end) throws IOException {
		if (end - start < 8) return true;
		byte[] h = source.read(start, 8);
		if (h.length < 8) return true;
		long count = u32(h, 4);
		long pos = start + 8;
		for (long i = 0; i < count; i++) {
			if (i >= MAX_DEPTH_BOXES || end - pos < 12) return true;
			byte[] e = source.read(pos, 16);
			long[] box = box(e, end - pos);
			if (box == null || box[1] != 8) return true;
			if (!MediaMagic.ascii(e, 4, "url ")) return true;
			long flags = u32(e, 8) & 0xFFFFFF;
			if ((flags & 1) == 0) return true;
			if (box[0] != 12) return true;
			pos += box[0];
		}
		return pos != end;
	}

	@Nullable
	private static long[] box(byte[] h, long remaining) {
		long size = u32(h, 0);
		int header = 8;
		if (size == 1) {
			if (h.length < 16) return null;
			size = (u32(h, 8) << 32) | u32(h, 12);
			header = 16;
			if (size < 0) return null;
		} else if (size == 0) {
			size = remaining;
		}
		if (size < header) return null;
		return new long[] {size, header};
	}

	private static long u32(byte[] d, int off) {
		return ((d[off] & 0xFFL) << 24) | ((d[off + 1] & 0xFFL) << 16)
				| ((d[off + 2] & 0xFFL) << 8) | (d[off + 3] & 0xFFL);
	}

	private static long u32le(byte[] d, int off) {
		return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8)
				| ((d[off + 2] & 0xFFL) << 16) | ((d[off + 3] & 0xFFL) << 24);
	}

	interface Source {

		long length() throws IOException;

		byte[] read(long pos, int n) throws IOException;

		default byte[] head(int n) throws IOException {
			return read(0, n);
		}
	}

	static final class ArraySource implements Source {

		private final byte[] data;

		ArraySource(byte[] data) {
			this.data = data;
		}

		@Override
		public long length() {
			return data.length;
		}

		@Override
		public byte[] read(long pos, int n) {
			if (pos >= data.length) return new byte[0];
			int from = (int) pos;
			return Arrays.copyOfRange(data, from,
					(int) Math.min(data.length, from + (long) n));
		}
	}

	private static final class Slice implements Source {

		private final Source source;
		private final long offset;

		Slice(Source source, long offset) {
			this.source = source;
			this.offset = offset;
		}

		@Override
		public long length() throws IOException {
			return Math.max(0, source.length() - offset);
		}

		@Override
		public byte[] read(long pos, int n) throws IOException {
			return source.read(offset + pos, n);
		}
	}

	private static final class FileSource implements Source {

		private final RandomAccessFile file;

		FileSource(RandomAccessFile file) {
			this.file = file;
		}

		@Override
		public long length() throws IOException {
			return file.length();
		}

		@Override
		public byte[] read(long pos, int n) throws IOException {
			long len = file.length();
			if (pos >= len) return new byte[0];
			byte[] out = new byte[(int) Math.min(n, len - pos)];
			file.seek(pos);
			file.readFully(out);
			return out;
		}
	}
}

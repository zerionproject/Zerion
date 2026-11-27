package com.professor.zerion.android.attachment;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
public final class AttachmentDocuments {

	public static final String PDF = "application/pdf";
	public static final String TEXT = "text/plain";

	private static final int PDF_HEADER_WINDOW = 1024;

	private AttachmentDocuments() {
	}

	public static String[] pickerTypes() {
		return new String[] {PDF, TEXT, "text/markdown", "text/x-markdown"};
	}

	private static String base(@Nullable String type) {
		if (type == null) return "";
		int semi = type.indexOf(';');
		String b = semi >= 0 ? type.substring(0, semi) : type;
		return b.trim().toLowerCase(Locale.US);
	}

	public static boolean isDocumentType(@Nullable String contentType) {
		String b = base(contentType);
		return b.equals(PDF) || b.equals(TEXT);
	}

	@Nullable
	public static String sendType(@Nullable String declared) {
		String b = base(declared);
		if (b.equals(PDF)) return PDF;
		if (b.equals(TEXT) || b.equals("text/markdown")
				|| b.equals("text/x-markdown")) {
			return TEXT;
		}
		return null;
	}

	private static final String[] STRUCTURED_STARTS = {"%!ps", "{\\rtf"};

	public static boolean isPdf(byte[] data) {
		return contentMatches(PDF, data);
	}

	public static boolean isSendableText(byte[] data) {
		if (data.length == 0) return false;
		for (byte b : data) {
			int c = b & 0xFF;
			if (c == 0x7F || (c < 0x20 && c != '\t' && c != '\n'
					&& c != '\r' && c != 0x0C)) {
				return false;
			}
		}
		if (isPdf(data)) return false;
		int i = skipLeading(data, 0);
		for (String start : STRUCTURED_STARTS) {
			if (startsWithIgnoringCase(data, i, start)) return false;
		}
		return !startsMarkup(data, i);
	}

	private static final Set<String> METADATA_ROOTS = new HashSet<>(
			Arrays.asList("svg", "html", "xhtml", "head", "body", "xml",
					"gpx", "kml", "kmz", "xmpmeta", "rdf", "plist", "rss",
					"feed"));

	private static final int MAX_NAME = 128;
	private static final int MAX_TAG = 4096;

	private static int skipLeading(byte[] data, int from) {
		int i = from;
		while (i < data.length) {
			long next = codePointAt(data, i);
			int cp = (int) next;
			int length = (int) (next >>> 32);
			if (length == 0) {
				int b = data[i] & 0xFF;
				if (b != 0xA0 && b != 0x85) return i;
				i++;
				continue;
			}
			if (!skippable(cp)) return i;
			i += length;
		}
		return i;
	}

	private static boolean skippable(int cp) {
		return Character.isWhitespace(cp) || Character.isSpaceChar(cp)
				|| Character.getType(cp) == Character.FORMAT || cp == 0x85;
	}

	private static long codePointAt(byte[] data, int i) {
		int b = data[i] & 0xFF;
		int length;
		int cp;
		if (b < 0x80) {
			return (1L << 32) | b;
		} else if ((b & 0xE0) == 0xC0) {
			length = 2;
			cp = b & 0x1F;
		} else if ((b & 0xF0) == 0xE0) {
			length = 3;
			cp = b & 0x0F;
		} else if ((b & 0xF8) == 0xF0) {
			length = 4;
			cp = b & 0x07;
		} else {
			return 0L;
		}
		if (i + length > data.length) return 0L;
		for (int k = 1; k < length; k++) {
			int c = data[i + k] & 0xFF;
			if ((c & 0xC0) != 0x80) return 0L;
			cp = (cp << 6) | (c & 0x3F);
		}
		return ((long) length << 32) | (cp & 0xFFFFFFFFL);
	}

	private static boolean startsMarkup(byte[] data, int at) {
		if (data.length - at < 2 || data[at] != '<') return false;
		int c = data[at + 1] & 0xFF;
		if (c == '?' || c == '!') return true;
		StringBuilder name = new StringBuilder();
		int i = at + 1;
		while (i < data.length && name.length() < MAX_NAME) {
			long next = codePointAt(data, i);
			int length = (int) (next >>> 32);
			if (length == 0) break;
			int cp = (int) next;
			if (cp == '>' || cp == '/' || cp == '<' || cp == '='
					|| skippable(cp)) {
				break;
			}
			name.appendCodePoint(cp);
			i += length;
		}
		if (name.length() == 0) return false;
		int first = name.codePointAt(0);
		if (!Character.isLetter(first) && first != '_' && first != ':') {
			return false;
		}
		String root = name.toString().toLowerCase(Locale.ROOT);
		if (root.indexOf(':') >= 0 || METADATA_ROOTS.contains(root)) {
			return true;
		}
		return declaresNamespace(data, i);
	}

	private static boolean declaresNamespace(byte[] data, int from) {
		int end = Math.min(data.length, from + MAX_TAG);
		for (int i = from; i < end; i++) {
			if (data[i] == '>') return false;
			if (startsWithIgnoringCase(data, i, "xmlns")) {
				int j = i + 5;
				while (j < end && (data[j] == ' ' || data[j] == '\t'
						|| data[j] == '\n' || data[j] == '\r')) {
					j++;
				}
				if (j < end && (data[j] == '=' || data[j] == ':')) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean startsWithIgnoringCase(byte[] data, int at,
			String lower) {
		if (data.length - at < lower.length()) return false;
		for (int i = 0; i < lower.length(); i++) {
			int c = data[at + i] & 0xFF;
			if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
			if (c != lower.charAt(i)) return false;
		}
		return true;
	}

	static boolean contentMatches(String type, byte[] data) {
		if (data.length == 0) return false;
		if (PDF.equals(type)) {
			int end = Math.min(data.length, PDF_HEADER_WINDOW) - 5;
			for (int i = 0; i <= end; i++) {
				if (data[i] == '%' && data[i + 1] == 'P' && data[i + 2] == 'D'
						&& data[i + 3] == 'F' && data[i + 4] == '-') {
					return true;
				}
			}
			return false;
		}
		for (byte b : data) {
			if (b == 0) return false;
		}
		return true;
	}
}

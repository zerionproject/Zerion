package com.professor.zerion.android.attachment;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

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

	private static final String[] STRUCTURED_STARTS = {"%!ps", "{\\rtf",
			"<?xml", "<svg", "<!doctype", "<html", "<head", "<body"};

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
		int i = 0;
		if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
				&& (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
			i = 3;
		}
		while (i < data.length && (data[i] == ' ' || data[i] == '\t'
				|| data[i] == '\n' || data[i] == '\r' || data[i] == 0x0C)) {
			i++;
		}
		for (String start : STRUCTURED_STARTS) {
			if (startsWithIgnoringCase(data, i, start)) return false;
		}
		return true;
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

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

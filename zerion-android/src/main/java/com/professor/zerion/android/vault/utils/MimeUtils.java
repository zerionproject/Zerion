package com.professor.zerion.android.vault.utils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@NotNullByDefault
public class MimeUtils {

	private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] PNG_MAGIC = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
	private static final byte[] JPEG_MAGIC = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
	private static final byte[] GIF_MAGIC = "GIF8".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] WEBP_MAGIC = "WEBP".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] ZIP_MAGIC = new byte[]{0x50, 0x4B, 0x03, 0x04};
	private static final byte[] DOC_MAGIC = new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};

	private static final byte[] MP4_MAGIC = "ftyp".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] WEBM_MAGIC = new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

	public enum MimeType {
		PDF("application/pdf", true, "PDF Document"),
		TEXT("text/plain", true, "Text File"),
		MARKDOWN("text/markdown", true, "Markdown Document"),

		IMAGE_PNG("image/png", true, "PNG Image"),
		IMAGE_JPEG("image/jpeg", true, "JPEG Image"),
		IMAGE_GIF("image/gif", true, "GIF Image"),
		IMAGE_WEBP("image/webp", true, "WebP Image"),

		VIDEO_MP4("video/mp4", false, "MP4 Video"),
		VIDEO_WEBM("video/webm", false, "WebM Video"),

		OFFICE_DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", false, "Word Document"),
		OFFICE_DOC("application/msword", false, "Word Document (Legacy)"),
		OFFICE_XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false, "Excel Spreadsheet"),
		OFFICE_XLS("application/vnd.ms-excel", false, "Excel Spreadsheet (Legacy)"),
		OFFICE_PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", false, "PowerPoint Presentation"),
		OFFICE_PPT("application/vnd.ms-powerpoint", false, "PowerPoint Presentation (Legacy)"),

		ARCHIVE_ZIP("application/zip", false, "ZIP Archive"),
		ARCHIVE_RAR("application/x-rar-compressed", false, "RAR Archive"),
		ARCHIVE_7Z("application/x-7z-compressed", false, "7-Zip Archive"),

		UNKNOWN("application/octet-stream", false, "Unknown File");

		public final String mimeType;
		public final boolean canViewSecurely;
		public final String displayName;

		MimeType(String mimeType, boolean canViewSecurely, String displayName) {
			this.mimeType = mimeType;
			this.canViewSecurely = canViewSecurely;
			this.displayName = displayName;
		}
	}

	public static MimeType detectMimeType(byte[] content, String filename) {
		if (content == null || content.length == 0) {
			return MimeType.UNKNOWN;
		}

		MimeType magicType = detectByMagicBytes(content);
		if (magicType != MimeType.UNKNOWN) {
			return magicType;
		}

		if (filename != null && !filename.isEmpty()) {
			return detectByExtension(filename);
		}

		if (isTextContent(content)) {
			if (filename != null && (filename.endsWith(".md") || filename.endsWith(".markdown"))) {
				return MimeType.MARKDOWN;
			}
			return MimeType.TEXT;
		}

		return MimeType.UNKNOWN;
	}

	private static MimeType detectByMagicBytes(byte[] content) {
		if (content.length < 8) {
			return MimeType.UNKNOWN;
		}

		if (startsWith(content, PDF_MAGIC)) {
			return MimeType.PDF;
		}

		if (startsWith(content, PNG_MAGIC)) {
			return MimeType.IMAGE_PNG;
		}

		if (startsWith(content, JPEG_MAGIC)) {
			return MimeType.IMAGE_JPEG;
		}

		if (startsWith(content, GIF_MAGIC)) {
			return MimeType.IMAGE_GIF;
		}

		if (content.length >= 12 && content[8] == 'W' && content[9] == 'E'
				&& content[10] == 'B' && content[11] == 'P') {
			return MimeType.IMAGE_WEBP;
		}

		if (content.length >= 12 && startsWith(content, 4, MP4_MAGIC)) {
			return MimeType.VIDEO_MP4;
		}

		if (startsWith(content, WEBM_MAGIC)) {
			return MimeType.VIDEO_WEBM;
		}

		if (startsWith(content, ZIP_MAGIC)) {
			return MimeType.ARCHIVE_ZIP;
		}

		if (startsWith(content, DOC_MAGIC)) {
			return MimeType.OFFICE_DOC;
		}

		if (content.length >= 4 && content[0] == 'R' && content[1] == 'a'
				&& content[2] == 'r' && content[3] == '!') {
			return MimeType.ARCHIVE_RAR;
		}

		if (content.length >= 6 && content[0] == '7' && content[1] == 'z'
				&& content[2] == (byte) 0xBC && content[3] == (byte) 0xAF
				&& content[4] == 0x27 && content[5] == 0x1C) {
			return MimeType.ARCHIVE_7Z;
		}

		return MimeType.UNKNOWN;
	}

	private static MimeType detectByExtension(String filename) {
		String lower = filename.toLowerCase();

		int lastDot = lower.lastIndexOf('.');
		if (lastDot < 0 || lastDot == lower.length() - 1) {
			return MimeType.UNKNOWN;
		}

		String ext = lower.substring(lastDot + 1);

		switch (ext) {
			case "pdf":
				return MimeType.PDF;
			case "txt":
				return MimeType.TEXT;
			case "md":
			case "markdown":
				return MimeType.MARKDOWN;

			case "png":
				return MimeType.IMAGE_PNG;
			case "jpg":
			case "jpeg":
				return MimeType.IMAGE_JPEG;
			case "gif":
				return MimeType.IMAGE_GIF;
			case "webp":
				return MimeType.IMAGE_WEBP;

			case "mp4":
			case "m4v":
				return MimeType.VIDEO_MP4;
			case "webm":
				return MimeType.VIDEO_WEBM;

			case "docx":
				return MimeType.OFFICE_DOCX;
			case "doc":
				return MimeType.OFFICE_DOC;
			case "xlsx":
				return MimeType.OFFICE_XLSX;
			case "xls":
				return MimeType.OFFICE_XLS;
			case "pptx":
				return MimeType.OFFICE_PPTX;
			case "ppt":
				return MimeType.OFFICE_PPT;

			case "zip":
				return MimeType.ARCHIVE_ZIP;
			case "rar":
				return MimeType.ARCHIVE_RAR;
			case "7z":
				return MimeType.ARCHIVE_7Z;

			default:
				return MimeType.UNKNOWN;
		}
	}

	private static boolean isTextContent(byte[] content) {
		int checkLength = Math.min(content.length, 512);
		int textBytes = 0;
		int controlBytes = 0;

		for (int i = 0; i < checkLength; i++) {
			byte b = content[i];

			if ((b >= 0x20 && b <= 0x7E) || b == '\n' || b == '\r' || b == '\t') {
				textBytes++;
			}
			else if ((b & 0xC0) == 0x80) {
				textBytes++;
			}
			else if (b == 0x00 || (b < 0x20 && b != '\n' && b != '\r' && b != '\t')) {
				controlBytes++;
			}
		}

		return (textBytes > checkLength * 0.8) && (controlBytes < checkLength * 0.05);
	}

	private static boolean startsWith(byte[] content, byte[] prefix) {
		return startsWith(content, 0, prefix);
	}

	private static boolean startsWith(byte[] content, int offset, byte[] prefix) {
		if (content.length < offset + prefix.length) {
			return false;
		}

		for (int i = 0; i < prefix.length; i++) {
			if (content[offset + i] != prefix[i]) {
				return false;
			}
		}

		return true;
	}
}

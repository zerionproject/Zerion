package com.professor.zerion.android.attachment;

import com.professor.zerion.R;
import com.professor.zerion.android.util.MediaMagic;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.annotation.Nullable;

import androidx.annotation.StringRes;

@NotNullByDefault
public final class CleanFilePolicy {

	public enum Cleaner {
		OFFICE,
		SHARED
	}

	public enum Outcome {
		OFFICE,
		IMAGE,
		MEDIA,
		PDF,
		TEXT
	}

	static final String OFFICE_PREFIX =
			"application/vnd.openxmlformats-officedocument";
	public static final String WORD = OFFICE_PREFIX
			+ ".wordprocessingml.document";
	public static final String SHEET = OFFICE_PREFIX
			+ ".spreadsheetml.sheet";
	public static final String SLIDES = OFFICE_PREFIX
			+ ".presentationml.presentation";

	private static final String CONTENT_TYPES = "[content_types].xml";

	private static final String[] HEIF_BRANDS = {"heic", "heix", "hevc",
			"hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1"};

	private static final int WORD_PART = 1;
	private static final int SHEET_PART = 2;
	private static final int SLIDES_PART = 4;
	private static final int CONTENT_TYPES_PART = 8;

	private CleanFilePolicy() {
	}

	public static boolean isZip(byte[] data) {
		return data.length >= 4 && data[0] == 'P' && data[1] == 'K'
				&& data[2] == 3 && data[3] == 4;
	}

	public static boolean isOfficeDocument(byte[] data) {
		return (parts(data) & CONTENT_TYPES_PART) != 0;
	}

	public static Cleaner cleanerFor(byte[] data) {
		return isOfficeDocument(data) ? Cleaner.OFFICE : Cleaner.SHARED;
	}

	public static String officeMimeType(byte[] data) {
		int parts = parts(data);
		if ((parts & WORD_PART) != 0) return WORD;
		if ((parts & SHEET_PART) != 0) return SHEET;
		if ((parts & SLIDES_PART) != 0) return SLIDES;
		return OFFICE_PREFIX;
	}

	static final int MAX_ENTRIES = 10_000;
	static final long MAX_INFLATED = 512L * 1024 * 1024;

	private static int parts(byte[] data) {
		if (!isZip(data)) return 0;
		int found = 0;
		try (ZipInputStream zin =
				new ZipInputStream(new ByteArrayInputStream(data))) {
			ZipEntry e;
			int entries = 0;
			long inflated = 0;
			byte[] buf = new byte[8192];
			int main = WORD_PART | SHEET_PART | SLIDES_PART;
			while ((e = zin.getNextEntry()) != null) {
				if (++entries > MAX_ENTRIES) return 0;
				String lower = e.getName().toLowerCase(Locale.ROOT);
				if (lower.equals(CONTENT_TYPES)) found |= CONTENT_TYPES_PART;
				else if (lower.startsWith("word/")) found |= WORD_PART;
				else if (lower.startsWith("xl/")) found |= SHEET_PART;
				else if (lower.startsWith("ppt/")) found |= SLIDES_PART;
				if ((found & CONTENT_TYPES_PART) != 0 && (found & main) != 0) {
					break;
				}
				int read;
				while ((read = zin.read(buf)) > 0) {
					inflated += read;
					if (inflated > MAX_INFLATED) return 0;
				}
			}
		} catch (IOException | RuntimeException e) {
			return 0;
		}
		return found;
	}

	public static boolean mayDecodeInAppProcess(byte[] data) {
		return MediaMagic.isBmp(data) || MediaMagic.isAvif(data)
				|| isHeif(data);
	}

	public static boolean isHeif(byte[] data) {
		if (data.length < 12 || data[4] != 'f' || data[5] != 't'
				|| data[6] != 'y' || data[7] != 'p') {
			return false;
		}
		for (String brand : HEIF_BRANDS) {
			if (data[8] == brand.charAt(0) && data[9] == brand.charAt(1)
					&& data[10] == brand.charAt(2)
					&& data[11] == brand.charAt(3)) {
				return true;
			}
		}
		return false;
	}

	public static Outcome outcomeFor(Cleaner cleaner,
			@Nullable String cleanedMime) {
		if (cleaner == Cleaner.OFFICE) return Outcome.OFFICE;
		String type = base(cleanedMime);
		if (type.startsWith("image/")) return Outcome.IMAGE;
		if (type.startsWith("video/") || type.startsWith("audio/")) {
			return Outcome.MEDIA;
		}
		if (type.equals("application/pdf")) return Outcome.PDF;
		if (type.equals("text/plain")) return Outcome.TEXT;
		throw new IllegalArgumentException("no wording for " + type);
	}

	@StringRes
	public static int explanation(Outcome outcome) {
		switch (outcome) {
			case OFFICE:
				return R.string.clean_file_done_office;
			case IMAGE:
				return R.string.clean_file_done_image;
			case MEDIA:
				return R.string.clean_file_done_media;
			case PDF:
				return R.string.clean_file_done_pdf;
			default:
				return R.string.clean_file_done_text;
		}
	}

	static String base(@Nullable String type) {
		if (type == null) return "";
		int semi = type.indexOf(';');
		String b = semi >= 0 ? type.substring(0, semi) : type;
		return b.trim().toLowerCase(Locale.ROOT);
	}
}

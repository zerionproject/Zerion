package com.professor.zerion.android.util;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.FileNotFoundException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

@NotNullByDefault
public final class SavedFileNames {

	private static final Pattern PLAIN_EXTENSION =
			Pattern.compile("[a-z0-9]{1,8}");

	private SavedFileNames() {
	}

	public static String named(String kind, String mimeType, long now) {
		String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
				.format(new Date(now));
		String ext = extension(mimeType);
		String tail = ext.isEmpty() ? stamp : stamp + "." + ext;
		String label = leading(kind.trim(),
				SafeFileNames.MAX_LENGTH - tail.length() - 2);
		return SafeFileNames.sanitize(label.isEmpty() ? tail
				: label + " " + tail, tail);
	}

	private static String leading(String s, int codePoints) {
		if (codePoints <= 0) return "";
		int end = 0;
		int n = 0;
		while (end < s.length() && n < codePoints) {
			end += Character.charCount(s.codePointAt(end));
			n++;
		}
		return s.substring(0, end);
	}

	static String extension(String mimeType) {
		String type = mimeType.toLowerCase(Locale.ROOT);
		int semi = type.indexOf(';');
		if (semi >= 0) type = type.substring(0, semi);
		type = type.trim();
		switch (type) {
			case "image/jpeg":
				return "jpg";
			case "audio/mp4":
				return "m4a";
			case "audio/mpeg":
				return "mp3";
			case "audio/wav":
			case "audio/wave":
			case "audio/x-wav":
				return "wav";
			case "text/plain":
				return "txt";
			default:
				break;
		}
		int slash = type.indexOf('/');
		if (slash < 0) return "";
		String sub = type.substring(slash + 1);
		if (sub.startsWith("x-")) sub = sub.substring(2);
		return PLAIN_EXTENSION.matcher(sub).matches() ? sub : "";
	}

	public static void discard(ContentResolver resolver, Uri uri) {
		try {
			DocumentsContract.deleteDocument(resolver, uri);
		} catch (FileNotFoundException | RuntimeException ignored) {
		}
	}
}

package com.professor.zerion.android.contact.request;

import android.util.Base64;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.keyagreement.PayloadParser;
import org.zerionproject.core.util.ZerionLinks;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

@NotNullByDefault
public final class ScannedCode {

	private static final Pattern LINK = Pattern.compile(
			ZerionLinks.SCHEME_PATTERN + "://[A-Za-z2-7]+(?:\\?[^\\s]*)?");

	private ScannedCode() {
	}

	@Nullable
	public static String extractLink(@Nullable CharSequence text) {
		if (text == null) return null;
		Matcher m = LINK.matcher(text);
		if (!m.find()) return null;
		return ZerionLinks.normalizeScheme(m.group());
	}

	public static boolean isNearbyCode(String text, PayloadParser parser) {
		if (text.isEmpty() || ZerionLinks.hasZerionScheme(text.trim())) {
			return false;
		}
		byte[] raw;
		try {
			raw = Base64.decode(text, Base64.NO_WRAP);
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (raw.length == 0) return false;
		try {
			parser.parse(new String(raw, ISO_8859_1));
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}
}

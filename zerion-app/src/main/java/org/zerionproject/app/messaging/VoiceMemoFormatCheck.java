package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Checks the voice memo text forms an incoming private message may carry. A
 * memo is the text {@code [VOICE:<durationMs>:<base64 payload>]} or, when
 * long, a series of parts {@code [VMP:1:<memoId>:<seq>:<total>:<durationMs>:
 * <slice>]} whose slices concatenate to that base64 payload. The payload's
 * first byte is its format version. Version 1 carried its wrap key in clear
 * and bound no message identity into the associated data, so it is refused
 * on receipt: a memo in that format is neither played nor kept, and cannot be
 * replayed or reflected into a conversation. Only the first part of a chunked
 * memo carries the version byte, so only that part is checked.
 */
@NotNullByDefault
class VoiceMemoFormatCheck {

	static final byte CURRENT_FORMAT_VERSION = 2;

	private static final Pattern VOICE =
			Pattern.compile("\\[VOICE:(\\d+):([A-Za-z0-9+/=]+)\\]");
	private static final Pattern PART = Pattern.compile(
			"\\[VMP:1:([0-9a-f]{16}):(\\d+):(\\d+):(\\d+):([A-Za-z0-9+/=]*)\\]");

	private VoiceMemoFormatCheck() {
	}

	/**
	 * Throws if {@code text} is a voice memo, or the first part of one, in
	 * any format but the current one.
	 */
	static void requireCurrentFormat(@Nullable String text)
			throws FormatException {
		if (text == null) return;
		String base64;
		if (text.startsWith("[VOICE:")) {
			Matcher m = VOICE.matcher(text);
			if (!m.matches()) throw new FormatException();
			base64 = m.group(2);
		} else if (text.startsWith("[VMP:1:")) {
			Matcher m = PART.matcher(text);
			if (!m.matches()) throw new FormatException();
			if (!"0".equals(m.group(2))) return;
			base64 = m.group(5);
		} else {
			return;
		}
		if (formatVersion(base64) != CURRENT_FORMAT_VERSION) {
			throw new FormatException();
		}
	}

	private static int formatVersion(String base64) throws FormatException {
		if (base64.length() < 4) throw new FormatException();
		try {
			byte[] head = Base64.getDecoder().decode(base64.substring(0, 4));
			if (head.length < 1) throw new FormatException();
			return head[0];
		} catch (IllegalArgumentException e) {
			throw new FormatException();
		}
	}
}

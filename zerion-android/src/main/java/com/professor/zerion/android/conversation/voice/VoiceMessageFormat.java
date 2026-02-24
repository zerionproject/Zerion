package com.professor.zerion.android.conversation.voice;

import android.util.Base64;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@NotNullByDefault
public class VoiceMessageFormat {

	private static final String VOICE_PREFIX = "[VOICE:";
	private static final String VOICE_SUFFIX = "]";
	private static final Pattern VOICE_PATTERN =
		Pattern.compile("\\[VOICE:(\\d+):([A-Za-z0-9+/=]+)\\]");
	private static final int MAX_BINARY_PAYLOAD_SIZE = 280_000;
	private static final int MAX_BASE64_TEXT_SIZE = 375_000;
	private static final int BASE64_OVERHEAD_FACTOR = 4;

	public static class ParsedVoiceMessage {
		private final int durationMs;
		private final byte[] payload;

		ParsedVoiceMessage(int durationMs, byte[] payload) {
			this.durationMs = durationMs;
			this.payload = payload;
		}

		public int getDurationMs() {
			return durationMs;
		}

		public byte[] getPayload() {
			return payload;
		}

		public String getFormattedDuration() {
			int seconds = durationMs / 1000;
			int minutes = seconds / 60;
			int remainingSeconds = seconds % 60;
			return String.format("%d:%02d", minutes, remainingSeconds);
		}
	}

	public static int getMaxPayloadSize() {
		return MAX_BINARY_PAYLOAD_SIZE;
	}

	public static String format(int durationMs, byte[] payload) {
		if (payload.length > MAX_BINARY_PAYLOAD_SIZE) {
			throw new IllegalArgumentException(
				"Voice message payload too large for text format: " + payload.length +
				" bytes (max " + MAX_BINARY_PAYLOAD_SIZE + " bytes). " +
				"Consider implementing a dedicated VoiceMessage protocol type."
			);
		}

		String encodedPayload = Base64.encodeToString(payload,
			Base64.NO_WRAP | Base64.NO_PADDING);

		String messageText = VOICE_PREFIX + durationMs + ":" + encodedPayload + VOICE_SUFFIX;
		if (messageText.length() > MAX_BASE64_TEXT_SIZE) {
			throw new IllegalArgumentException(
				"Voice message text too large: " + messageText.length() +
				" chars (max " + MAX_BASE64_TEXT_SIZE + " chars). " +
				"This may cause message truncation or transmission failure."
			);
		}

		return messageText;
	}

	public static boolean isVoiceMessage(@Nullable String messageText) {
		if (messageText == null) return false;
		return messageText.startsWith(VOICE_PREFIX) && messageText.endsWith(VOICE_SUFFIX);
	}

	@Nullable
	public static ParsedVoiceMessage parse(@Nullable String messageText) {
		if (messageText == null || !isVoiceMessage(messageText)) {
			return null;
		}

		Matcher matcher = VOICE_PATTERN.matcher(messageText);
		if (!matcher.matches()) {
			return null;
		}

		try {
			int durationMs = Integer.parseInt(matcher.group(1));
			String base64Payload = matcher.group(2);

			byte[] payload = Base64.decode(base64Payload,
				Base64.NO_WRAP | Base64.NO_PADDING);

			return new ParsedVoiceMessage(durationMs, payload);

		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static int extractDuration(@Nullable String messageText) {
		if (messageText == null || !isVoiceMessage(messageText)) {
			return -1;
		}

		Matcher matcher = VOICE_PATTERN.matcher(messageText);
		if (!matcher.matches()) {
			return -1;
		}

		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	public static String getDisplayText(int durationMs) {
		int seconds = durationMs / 1000;
		int minutes = seconds / 60;
		int remainingSeconds = seconds % 60;
		return String.format("Voice Message (%d:%02d)", minutes, remainingSeconds);
	}
}

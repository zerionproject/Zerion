package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@NotNullByDefault
public class VoiceMessageChunkFormat {

	private static final String PART_PREFIX = "[VMP:1:";
	private static final String PART_SUFFIX = "]";
	private static final Pattern PART_PATTERN = Pattern.compile(
		"\\[VMP:1:([0-9a-f]{16}):(\\d+):(\\d+):(\\d+):([A-Za-z0-9+/=]*)\\]");

	public static final int CHUNK_THRESHOLD_CHARS = 24_000;
	private static final int SLICE_CHARS = 16_000;
	private static final int MEMO_ID_BYTES = 8;
	private static final int MAX_PARTS = 24;

	private static final SecureRandom RANDOM = new SecureRandom();

	public static class Part {
		public final String memoId;
		public final int seq;
		public final int total;
		public final int durationMs;
		public final String slice;

		Part(String memoId, int seq, int total, int durationMs, String slice) {
			this.memoId = memoId;
			this.seq = seq;
			this.total = total;
			this.durationMs = durationMs;
			this.slice = slice;
		}
	}

	public static boolean shouldChunk(@Nullable String voiceText) {
		return voiceText != null
			&& VoiceMessageFormat.isVoiceMessage(voiceText)
			&& voiceText.length() > CHUNK_THRESHOLD_CHARS;
	}

	public static boolean isPart(@Nullable String text) {
		return text != null
			&& text.startsWith(PART_PREFIX)
			&& text.endsWith(PART_SUFFIX);
	}

	public static String newMemoId() {
		byte[] bytes = new byte[MEMO_ID_BYTES];
		RANDOM.nextBytes(bytes);
		StringBuilder sb = new StringBuilder(MEMO_ID_BYTES * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

	public static List<String> split(String voiceText, String memoId) {
		int durationMs = VoiceMessageFormat.extractDuration(voiceText);
		String body = VoiceMessageFormat.getBase64Body(voiceText);
		if (durationMs < 0 || body == null) {
			throw new IllegalArgumentException("Not a voice message");
		}
		int total = (body.length() + SLICE_CHARS - 1) / SLICE_CHARS;
		if (total < 1) total = 1;
		if (total > MAX_PARTS) {
			throw new IllegalArgumentException("Voice message has too many parts");
		}
		List<String> parts = new ArrayList<>(total);
		for (int seq = 0; seq < total; seq++) {
			int start = seq * SLICE_CHARS;
			int end = Math.min(start + SLICE_CHARS, body.length());
			String slice = body.substring(start, end);
			parts.add(PART_PREFIX + memoId + ":" + seq + ":" + total + ":"
				+ durationMs + ":" + slice + PART_SUFFIX);
		}
		return parts;
	}

	@Nullable
	public static Part parse(@Nullable String text) {
		if (!isPart(text)) {
			return null;
		}
		Matcher matcher = PART_PATTERN.matcher(text);
		if (!matcher.matches()) {
			return null;
		}
		try {
			String memoId = matcher.group(1);
			int seq = Integer.parseInt(matcher.group(2));
			int total = Integer.parseInt(matcher.group(3));
			int durationMs = Integer.parseInt(matcher.group(4));
			String slice = matcher.group(5);
			if (total < 1 || total > MAX_PARTS || seq < 0 || seq >= total) {
				return null;
			}
			if (slice.length() > SLICE_CHARS) {
				return null;
			}
			return new Part(memoId, seq, total, durationMs, slice);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static String reassemble(int durationMs, List<String> orderedSlices) {
		StringBuilder body = new StringBuilder();
		for (String slice : orderedSlices) {
			body.append(slice);
		}
		return VoiceMessageFormat.buildFromBase64(durationMs, body.toString());
	}
}

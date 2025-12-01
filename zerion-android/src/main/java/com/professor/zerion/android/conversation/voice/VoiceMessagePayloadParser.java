package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@NotNullByDefault
public class VoiceMessagePayloadParser {

	private static final byte EXPECTED_FORMAT_VERSION = 1;
	private static final int IV_LENGTH = 12;
	private static final int WRAPPED_KEY_LENGTH = 48;
	private static final int TAG_LENGTH = 16;
	private static final int INT_LENGTH = 4;
	private static final int HEADER_LENGTH = 1 + IV_LENGTH + WRAPPED_KEY_LENGTH + INT_LENGTH;
	private static final int MAX_CHUNK_SIZE = 1_000_000;
	private static final int MAX_DURATION_MS = 600_000;
	private static final int MAX_PAYLOAD_SIZE = 2_000_000;

	public static class ParsedPayload {
		public final byte formatVersion;
		public final byte[] iv;
		public final byte[] wrappedKey;
		public final List<byte[]> chunks;
		public final List<byte[]> tags;
		public final int durationMs;
		public final byte[] globalMAC;

		public ParsedPayload(byte formatVersion, byte[] iv, byte[] wrappedKey,
		                     List<byte[]> chunks, List<byte[]> tags, int durationMs, byte[] globalMAC) {
			this.formatVersion = formatVersion;
			this.iv = iv;
			this.wrappedKey = wrappedKey;
			this.chunks = chunks;
			this.tags = tags;
			this.durationMs = durationMs;
			this.globalMAC = globalMAC;
		}

		public void zeroize() {
			Arrays.fill(iv, (byte) 0);
			Arrays.fill(wrappedKey, (byte) 0);
			for (byte[] chunk : chunks) {
				Arrays.fill(chunk, (byte) 0);
			}
			for (byte[] tag : tags) {
				Arrays.fill(tag, (byte) 0);
			}
			Arrays.fill(globalMAC, (byte) 0);
		}
	}

	public static ParsedPayload parse(byte[] payload) {
		if (payload.length > MAX_PAYLOAD_SIZE) {
			throw new IllegalArgumentException("Payload too large: " + payload.length +
				" bytes (max " + MAX_PAYLOAD_SIZE + " bytes)");
		}

		if (payload.length < HEADER_LENGTH + TAG_LENGTH + INT_LENGTH) {
			throw new IllegalArgumentException("Payload too short: " + payload.length +
				" bytes (minimum " + (HEADER_LENGTH + TAG_LENGTH + INT_LENGTH) + " required)");
		}

		int offset = 0;

		byte formatVersion = payload[offset++];
		if (formatVersion != EXPECTED_FORMAT_VERSION) {
			throw new IllegalArgumentException("Unsupported format version: " + formatVersion +
				" (expected " + EXPECTED_FORMAT_VERSION + ")");
		}

		byte[] iv = Arrays.copyOfRange(payload, offset, offset + IV_LENGTH);
		offset += IV_LENGTH;

		byte[] wrappedKey = Arrays.copyOfRange(payload, offset, offset + WRAPPED_KEY_LENGTH);
		offset += WRAPPED_KEY_LENGTH;

		int chunkCount = bytesToInt(payload, offset);
		offset += INT_LENGTH;

		if (chunkCount <= 0) {
			throw new IllegalArgumentException("Invalid chunk count: " + chunkCount);
		}

		List<byte[]> chunks = new ArrayList<>(chunkCount);
		List<byte[]> tags = new ArrayList<>(chunkCount);

		for (int i = 0; i < chunkCount; i++) {
			if (offset + INT_LENGTH > payload.length) {
				throw new IllegalArgumentException("Unexpected end of payload at chunk " + i +
					" length header (offset " + offset + ")");
			}

			int chunkLength = bytesToInt(payload, offset);
			offset += INT_LENGTH;

			if (chunkLength <= 0 || chunkLength > MAX_CHUNK_SIZE) {
				throw new IllegalArgumentException("Invalid chunk length: " + chunkLength +
					" at chunk " + i + " (must be 1-" + MAX_CHUNK_SIZE + " bytes)");
			}

			if (offset + chunkLength > payload.length) {
				throw new IllegalArgumentException("Unexpected end of payload at chunk " + i +
					" data (offset " + offset + ", length " + chunkLength + ")");
			}

			byte[] chunk = Arrays.copyOfRange(payload, offset, offset + chunkLength);
			chunks.add(chunk);
			offset += chunkLength;

			if (offset + TAG_LENGTH > payload.length) {
				throw new IllegalArgumentException("Unexpected end of payload at chunk " + i +
					" tag (offset " + offset + ")");
			}

			byte[] tag = Arrays.copyOfRange(payload, offset, offset + TAG_LENGTH);
			tags.add(tag);
			offset += TAG_LENGTH;
		}

		if (offset + INT_LENGTH + TAG_LENGTH != payload.length) {
			throw new IllegalArgumentException("Payload has unexpected trailing data: " +
				"expected " + (offset + INT_LENGTH + TAG_LENGTH) + " bytes, got " + payload.length);
		}

		int durationMs = bytesToInt(payload, offset);
		offset += INT_LENGTH;

		if (durationMs < 0 || durationMs > MAX_DURATION_MS) {
			throw new IllegalArgumentException("Invalid duration: " + durationMs +
				"ms (must be 0-" + MAX_DURATION_MS + "ms)");
		}

		byte[] globalMAC = Arrays.copyOfRange(payload, offset, offset + TAG_LENGTH);

		return new ParsedPayload(formatVersion, iv, wrappedKey, chunks, tags, durationMs, globalMAC);
	}

	public static int parseDuration(byte[] payload) {
		if (payload.length < HEADER_LENGTH + TAG_LENGTH + INT_LENGTH) {
			throw new IllegalArgumentException("Payload too short: " + payload.length +
				" bytes (minimum " + (HEADER_LENGTH + TAG_LENGTH + INT_LENGTH) + " required for duration)");
		}

		byte formatVersion = parseFormatVersion(payload);
		if (formatVersion != EXPECTED_FORMAT_VERSION) {
			throw new IllegalArgumentException("Unsupported format version: " + formatVersion);
		}

		return bytesToInt(payload, payload.length - TAG_LENGTH - INT_LENGTH);
	}

	public static byte parseFormatVersion(byte[] payload) {
		if (payload.length < 1) {
			throw new IllegalArgumentException("Payload is empty");
		}
		return payload[0];
	}

	private static int bytesToInt(byte[] data, int offset) {
		return ByteBuffer.wrap(data, offset, INT_LENGTH).getInt();
	}
}

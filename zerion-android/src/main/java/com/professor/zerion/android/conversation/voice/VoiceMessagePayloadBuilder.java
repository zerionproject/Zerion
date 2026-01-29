package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

@NotNullByDefault
public class VoiceMessagePayloadBuilder {

	private static final byte FORMAT_VERSION = 1;
	private static final int IV_LENGTH = 12;
	private static final int WRAPPED_KEY_LENGTH = 48;
	private static final int TAG_LENGTH = 16;
	private static final int INT_LENGTH = 4;
	private static final int MAX_CHUNK_SIZE = 8_192;
	private static final int MAX_DURATION_MS = 60_000;
	private static final int MAX_VOICE_MESSAGE_SIZE = 22_500;

	public static byte getFormatVersion() {
		return FORMAT_VERSION;
	}

	public static int getMaxChunkSize() {
		return MAX_CHUNK_SIZE;
	}

	public static int getMaxDurationMs() {
		return MAX_DURATION_MS;
	}

	public static byte[] build(byte[] iv, byte[] encryptedKey, List<byte[]> chunks,
	                            List<byte[]> tags, int durationMs, byte[] globalMAC) {
		if (iv.length != IV_LENGTH) {
			throw new IllegalArgumentException("IV must be 12 bytes, got " + iv.length);
		}
		if (encryptedKey.length != WRAPPED_KEY_LENGTH) {
			throw new IllegalArgumentException("Wrapped key must be 48 bytes, got " + encryptedKey.length);
		}
		if (chunks.isEmpty()) {
			throw new IllegalArgumentException("Must have at least one chunk");
		}
		if (chunks.size() != tags.size()) {
			throw new IllegalArgumentException("Chunk count (" + chunks.size() +
				") must match tag count (" + tags.size() + ")");
		}
		if (durationMs < 0 || durationMs > MAX_DURATION_MS) {
			throw new IllegalArgumentException("Invalid duration: " + durationMs +
				"ms (must be 0-" + MAX_DURATION_MS + "ms)");
		}
		if (globalMAC.length != TAG_LENGTH) {
			throw new IllegalArgumentException("Global MAC must be 16 bytes, got " + globalMAC.length);
		}

		int totalSizeEstimate = 1 + IV_LENGTH + WRAPPED_KEY_LENGTH + INT_LENGTH;
		for (int i = 0; i < chunks.size(); i++) {
			byte[] chunk = chunks.get(i);
			if (chunk.length == 0) {
				throw new IllegalArgumentException("Encrypted chunk " + i + " cannot be empty");
			}
			if (chunk.length > MAX_CHUNK_SIZE) {
				throw new IllegalArgumentException("Encrypted chunk " + i + " too large: " +
					chunk.length + " bytes (max " + MAX_CHUNK_SIZE + " bytes)");
			}
			totalSizeEstimate += INT_LENGTH + chunk.length + TAG_LENGTH;
		}
		totalSizeEstimate += INT_LENGTH + TAG_LENGTH;

		if (totalSizeEstimate > MAX_VOICE_MESSAGE_SIZE) {
			throw new IllegalArgumentException("Voice message exceeds maximum size: " +
				totalSizeEstimate + " bytes (max " + MAX_VOICE_MESSAGE_SIZE + " bytes)");
		}

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			baos.write(FORMAT_VERSION);
			baos.write(iv);
			baos.write(encryptedKey);
			baos.write(intToBytes(chunks.size()));

			for (int i = 0; i < chunks.size(); i++) {
				byte[] chunk = chunks.get(i);
				byte[] tag = tags.get(i);
				if (tag.length != TAG_LENGTH) {
					throw new IllegalArgumentException("Tag " + i + " must be 16 bytes, got " + tag.length);
				}
				baos.write(intToBytes(chunk.length));
				baos.write(chunk);
				baos.write(tag);
			}
			baos.write(intToBytes(durationMs));
			baos.write(globalMAC);
			return baos.toByteArray();
		} catch (IOException e) {
			throw new AssertionError("Unexpected IOException", e);
		}
	}

	private static byte[] intToBytes(int value) {
		return ByteBuffer.allocate(INT_LENGTH).putInt(value).array();
	}

	public static int parseDuration(byte[] payload) {
		if (payload.length < 20) {
			throw new IllegalArgumentException("Payload too short: " + payload.length +
				" bytes (minimum 20 required for duration+MAC)");
		}
		byte[] durationBytes = Arrays.copyOfRange(payload, payload.length - 20, payload.length - 16);
		int duration = ByteBuffer.wrap(durationBytes).getInt();
		Arrays.fill(durationBytes, (byte) 0);
		return duration;
	}
}

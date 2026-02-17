package com.professor.zerion.android.conversation.voice;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Secure in-memory holder for voice call keys, avoiding Android Intent extras
 * which are visible to other apps via IPC and may be logged by the system.
 * Keys are stored transiently and cleared after retrieval.
 */
@NotNullByDefault
public class VoiceCallKeyHolder {

	@Nullable
	private static volatile SecretKey pendingKey = null;

	@Nullable
	private static volatile byte[] pendingRemoteEphemeral = null;

	public static void setKey(SecretKey key) {
		pendingKey = key;
	}

	public static void setRemoteEphemeral(byte[] ephemeral) {
		pendingRemoteEphemeral = ephemeral;
	}

	@Nullable
	public static SecretKey consumeKey() {
		SecretKey key = pendingKey;
		pendingKey = null;
		return key;
	}

	@Nullable
	public static byte[] consumeRemoteEphemeral() {
		byte[] eph = pendingRemoteEphemeral;
		pendingRemoteEphemeral = null;
		return eph;
	}

	public static void clear() {
		pendingKey = null;
		pendingRemoteEphemeral = null;
	}
}

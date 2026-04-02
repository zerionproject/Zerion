package com.professor.zerion.android.conversation.voice;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;

@NotNullByDefault
public class VoiceCallKeyHolder {

	@Nullable
	private static SecretKey pendingKey = null;

	@Nullable
	private static byte[] pendingRemoteEphemeral = null;

	public static synchronized void setKey(SecretKey key) {
		SecretKey old = pendingKey;
		pendingKey = key;
		if (old != null) old.clear();
	}

	public static synchronized void setRemoteEphemeral(byte[] ephemeral) {
		byte[] old = pendingRemoteEphemeral;
		pendingRemoteEphemeral = ephemeral;
		if (old != null) Arrays.fill(old, (byte) 0);
	}

	@Nullable
	public static synchronized SecretKey consumeKey() {
		SecretKey key = pendingKey;
		pendingKey = null;
		return key;
	}

	@Nullable
	public static synchronized byte[] consumeRemoteEphemeral() {
		byte[] eph = pendingRemoteEphemeral;
		pendingRemoteEphemeral = null;
		return eph;
	}

	public static synchronized void clear() {
		SecretKey key = pendingKey;
		byte[] eph = pendingRemoteEphemeral;
		pendingKey = null;
		pendingRemoteEphemeral = null;
		if (key != null) key.clear();
		if (eph != null) Arrays.fill(eph, (byte) 0);
	}
}

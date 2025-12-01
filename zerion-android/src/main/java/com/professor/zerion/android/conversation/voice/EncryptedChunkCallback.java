package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
@SuppressWarnings("ArrayParameter")
public interface EncryptedChunkCallback {
	void onRecordingStarted();
	void onEncryptionInit(byte[] iv, byte[] encryptedKey);
	void onEncryptedChunk(byte[] encrypted, int len, byte[] tagPart);
	void onEncryptionFinal(byte[] globalMAC, int totalDurationMs, int chunkCount);
	void onRecordingProgress(int durationMs, int amplitudeDb);
	void onError(Exception e);
	void onCancelled();
}

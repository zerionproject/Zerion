package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface VoiceMemoKeys {

	byte[] deriveWrapKey(byte[] salt) throws Exception;

	byte[] localAuthorId() throws Exception;

	byte[] remoteAuthorId() throws Exception;

	long nextOutgoingTimestamp() throws Exception;
}

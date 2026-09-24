package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * The pairwise inputs a voice memo is sealed with and opened with. Both sides
 * of a conversation hold the same pairing secret, so the wrap key derived from
 * it for a memo's salt is the same on the recording and the playing device,
 * and the two author ids let each side bind a memo to its sender and its
 * recipient.
 */
@NotNullByDefault
public interface VoiceMemoKeys {

	/** The 32-byte wrap key for the memo carrying {@code salt}. */
	byte[] deriveWrapKey(byte[] salt) throws Exception;

	/** Our author id in this conversation. */
	byte[] localAuthorId() throws Exception;

	/** The contact's author id. */
	byte[] remoteAuthorId() throws Exception;

	/**
	 * The timestamp the next outgoing memo is stored with. Throws when the
	 * peer's messaging client is too old to receive the current memo format.
	 */
	long nextOutgoingTimestamp() throws Exception;
}

package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The parts of the voice memo format that decide what a memo is bound to.
 *
 * <p>Format 2: the 80-byte wrapped-key field is {@code salt(32) ||
 * AES-GCM(wrapKey, iv, sessionKey)(48)} where {@code wrapKey} is derived from
 * the pairing secret and the salt, so nothing in the payload opens it. Every
 * chunk and the global MAC carry as associated data {@code [2] || groupId ||
 * timestamp(8) || senderAuthorId(32) || recipientAuthorId(32)}, so a memo
 * verifies only as the message it was recorded for, from its sender to its
 * recipient, in its conversation. Format 1 carried the wrap key in the first
 * 32 bytes of the field and bound only the version and the group id.
 */
@NotNullByDefault
public final class VoiceMemoCrypto {

	public static final byte FORMAT_VERSION = 2;
	public static final byte LEGACY_FORMAT_VERSION = 1;
	public static final String WRAP_KEY_LABEL =
			"org.zerionproject.voice/MEMO_WRAP_KEY";
	public static final int SALT_LENGTH = 32;
	public static final int WRAP_KEY_LENGTH = 32;
	public static final int SEALED_SESSION_KEY_LENGTH = 48;
	public static final int WRAPPED_KEY_LENGTH =
			SALT_LENGTH + SEALED_SESSION_KEY_LENGTH;
	public static final int AUTHOR_ID_LENGTH = 32;

	private VoiceMemoCrypto() {
	}

	public static byte[] newSalt(SecureRandom random) {
		byte[] salt = new byte[SALT_LENGTH];
		random.nextBytes(salt);
		return salt;
	}

	public static byte[] wrappedKeyField(byte[] salt, byte[] sealedSessionKey) {
		if (salt.length != SALT_LENGTH) {
			throw new IllegalArgumentException("Salt must be " + SALT_LENGTH
					+ " bytes, got " + salt.length);
		}
		if (sealedSessionKey.length != SEALED_SESSION_KEY_LENGTH) {
			throw new IllegalArgumentException("Sealed session key must be "
					+ SEALED_SESSION_KEY_LENGTH + " bytes, got "
					+ sealedSessionKey.length);
		}
		byte[] field = new byte[WRAPPED_KEY_LENGTH];
		System.arraycopy(salt, 0, field, 0, SALT_LENGTH);
		System.arraycopy(sealedSessionKey, 0, field, SALT_LENGTH,
				SEALED_SESSION_KEY_LENGTH);
		return field;
	}

	/** The salt of a format 2 field, or the clear wrap key of a format 1 field. */
	public static byte[] fieldPrefix(byte[] wrappedKeyField) {
		requireField(wrappedKeyField);
		return Arrays.copyOfRange(wrappedKeyField, 0, SALT_LENGTH);
	}

	public static byte[] sealedSessionKey(byte[] wrappedKeyField) {
		requireField(wrappedKeyField);
		return Arrays.copyOfRange(wrappedKeyField, SALT_LENGTH,
				WRAPPED_KEY_LENGTH);
	}

	/**
	 * The message identity that follows the format version and the group id
	 * in the associated data of a format 2 memo.
	 */
	public static byte[] messageBinding(long timestamp, byte[] senderId,
			byte[] recipientId) {
		if (senderId.length != AUTHOR_ID_LENGTH
				|| recipientId.length != AUTHOR_ID_LENGTH) {
			throw new IllegalArgumentException("Author ids must be "
					+ AUTHOR_ID_LENGTH + " bytes");
		}
		ByteBuffer b = ByteBuffer.allocate(8 + 2 * AUTHOR_ID_LENGTH);
		b.putLong(timestamp);
		b.put(senderId);
		b.put(recipientId);
		return b.array();
	}

	private static void requireField(byte[] wrappedKeyField) {
		if (wrappedKeyField.length != WRAPPED_KEY_LENGTH) {
			throw new IllegalArgumentException("Wrapped key must be "
					+ WRAPPED_KEY_LENGTH + " bytes, got "
					+ wrappedKeyField.length);
		}
	}
}

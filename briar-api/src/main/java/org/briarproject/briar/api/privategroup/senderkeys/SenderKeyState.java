package org.briarproject.briar.api.privategroup.senderkeys;

/**
 * State machine for SenderKey lifecycle.
 * <p>
 * UNINITIALIZED → ACTIVE → ROTATING → ACTIVE
 *                   ↓
 *                REVOKED
 */
public enum SenderKeyState {

	/**
	 * Key has not been initialized yet.
	 */
	UNINITIALIZED(0),

	/**
	 * Key is active and can be used for encryption/decryption.
	 */
	ACTIVE(1),

	/**
	 * Key is being rotated (transitional state during rekey).
	 */
	ROTATING(2),

	/**
	 * Key has been revoked and should not be used for new messages.
	 * Retained temporarily for decrypting delayed messages.
	 */
	REVOKED(3);

	private final int value;

	SenderKeyState(int value) {
		this.value = value;
	}

	public int getValue() {
		return value;
	}

	public static SenderKeyState fromValue(int value) {
		for (SenderKeyState state : values()) {
			if (state.value == value) return state;
		}
		throw new IllegalArgumentException("Unknown SenderKeyState: " + value);
	}
}

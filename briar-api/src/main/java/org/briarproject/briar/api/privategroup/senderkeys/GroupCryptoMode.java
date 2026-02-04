package org.briarproject.briar.api.privategroup.senderkeys;

/**
 * Cryptographic mode for group messaging.
 * <p>
 * Mode selection is based on minimum common capability across all members.
 * Mode can only upgrade, never downgrade for existing members.
 */
public enum GroupCryptoMode {

	/**
	 * No end-to-end encryption (Briar compatibility mode).
	 * Used when any member lacks SENDER_KEYS capability.
	 */
	NONE(0),

	/**
	 * Full Sender Keys mode with per-sender key isolation.
	 * All members must support SENDER_KEYS_V1 capability.
	 */
	SENDER_KEYS(1),

	/**
	 * Degraded mode when some members have partial capability.
	 * Operates with reduced security guarantees.
	 */
	DEGRADED(2);

	private final int value;

	GroupCryptoMode(int value) {
		this.value = value;
	}

	public int getValue() {
		return value;
	}

	public static GroupCryptoMode fromValue(int value) {
		for (GroupCryptoMode mode : values()) {
			if (mode.value == value) return mode;
		}
		throw new IllegalArgumentException("Unknown GroupCryptoMode: " + value);
	}

	/**
	 * Returns true if this mode provides end-to-end encryption.
	 */
	public boolean isEncrypted() {
		return this == SENDER_KEYS;
	}
}

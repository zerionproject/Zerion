package org.briarproject.bramble.api.contact;

import org.briarproject.nullsafety.NotNullByDefault;

import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.FORMAT_VERSION_CLASSICAL;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.FORMAT_VERSION_HYBRID;

/**
 * Represents the explicitly chosen contact type when adding a new contact.
 * <p>
 * This enum ensures that the user explicitly selects whether they are adding
 * a Zerion contact (with post-quantum security) or a Briar contact (classical
 * cryptography for compatibility).
 * <p>
 * The contact type drives all protocol decisions:
 * <ul>
 *   <li>Link format (v0 classical vs v1 hybrid)</li>
 *   <li>Rendezvous mechanism</li>
 *   <li>Handshake cryptography</li>
 * </ul>
 */
@NotNullByDefault
public enum ContactType {

	/**
	 * Zerion contact with post-quantum cryptographic security.
	 * <p>
	 * Uses hybrid ML-KEM-768 + X25519 key exchange and ML-DSA-65 + Ed25519
	 * signatures. Link format version 1 with commitment-based exchange.
	 * <p>
	 * This type should be used when both parties are running Zerion.
	 */
	ZERION(FORMAT_VERSION_HYBRID, "Post-Quantum (Zerion)"),

	/**
	 * Briar-compatible contact with classical cryptography.
	 * <p>
	 * Uses X25519 key exchange and Ed25519 signatures.
	 * Link format version 0 for compatibility with standard Briar clients.
	 * <p>
	 * This type should be used when adding a contact who uses Briar
	 * (not Zerion), or for backward compatibility scenarios.
	 */
	BRIAR(FORMAT_VERSION_CLASSICAL, "Classical (Briar-compatible)");

	private final int formatVersion;
	private final String displayName;

	ContactType(int formatVersion, String displayName) {
		this.formatVersion = formatVersion;
		this.displayName = displayName;
	}

	/**
	 * Returns the link format version associated with this contact type.
	 */
	public int getFormatVersion() {
		return formatVersion;
	}

	/**
	 * Returns a human-readable display name for this contact type.
	 */
	public String getDisplayName() {
		return displayName;
	}

	/**
	 * Returns true if this contact type uses post-quantum cryptography.
	 */
	public boolean isPostQuantum() {
		return this == ZERION;
	}

	/**
	 * Returns the contact type corresponding to the given format version.
	 *
	 * @throws IllegalArgumentException if the format version is not recognized
	 */
	public static ContactType fromFormatVersion(int formatVersion) {
		for (ContactType type : values()) {
			if (type.formatVersion == formatVersion) {
				return type;
			}
		}
		throw new IllegalArgumentException(
				"Unknown format version: " + formatVersion);
	}
}

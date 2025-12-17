package org.briarproject.bramble.api.contact;

import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.FORMAT_VERSION_CLASSICAL;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.FORMAT_VERSION_HYBRID;

/**
 * Represents a pending contact awaiting key exchange completion.
 * <p>
 * The format version indicates which cryptographic protocol to use:
 * <ul>
 *   <li>Version 0 (classical): X25519 key exchange, Briar-compatible</li>
 *   <li>Version 1 (hybrid): X25519 + ML-KEM-768 key exchange, PQ-secure</li>
 * </ul>
 */
@Immutable
@NotNullByDefault
public class PendingContact {

	private final PendingContactId id;
	private final PublicKey publicKey;
	private final String alias;
	private final long timestamp;
	private final int formatVersion;

	/**
	 * Creates a pending contact with classical format (for backward compat).
	 */
	public PendingContact(PendingContactId id, PublicKey publicKey,
			String alias, long timestamp) {
		this(id, publicKey, alias, timestamp, FORMAT_VERSION_CLASSICAL);
	}

	/**
	 * Creates a pending contact with the specified format version.
	 *
	 * @param formatVersion 0 for classical (Briar), 1 for hybrid (PQ)
	 */
	public PendingContact(PendingContactId id, PublicKey publicKey,
			String alias, long timestamp, int formatVersion) {
		this.id = id;
		this.publicKey = publicKey;
		this.alias = alias;
		this.timestamp = timestamp;
		this.formatVersion = formatVersion;
	}

	public PendingContactId getId() {
		return id;
	}

	public PublicKey getPublicKey() {
		return publicKey;
	}

	public String getAlias() {
		return alias;
	}

	public long getTimestamp() {
		return timestamp;
	}

	/**
	 * Returns the handshake link format version.
	 *
	 * @return 0 for classical (Briar-compatible), 1 for hybrid (PQ-secure)
	 */
	public int getFormatVersion() {
		return formatVersion;
	}

	/**
	 * Returns true if this pending contact uses post-quantum cryptography.
	 */
	public boolean isPostQuantum() {
		return formatVersion == FORMAT_VERSION_HYBRID;
	}

	/**
	 * Returns true if this pending contact uses classical (Briar-compatible)
	 * cryptography.
	 */
	public boolean isClassical() {
		return formatVersion == FORMAT_VERSION_CLASSICAL;
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof PendingContact &&
				id.equals(((PendingContact) o).id);
	}
}

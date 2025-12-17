package org.briarproject.bramble.api.identity;

import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.crypto.CryptoConstants.KEY_TYPE_AGREEMENT;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_AGREEMENT;

/**
 * Represents the local user's identity, including both classical and
 * post-quantum (hybrid) handshake keys for version negotiation.
 * <p>
 * The dual-key approach enables:
 * <ul>
 *   <li>Zerion-to-Zerion: Uses hybrid PQ keys (version 1 links)</li>
 *   <li>Zerion-to-Briar: Uses classical X25519 keys (version 0 links)</li>
 * </ul>
 */
@Immutable
@NotNullByDefault
public class Identity {

	private final LocalAuthor localAuthor;

	// Classical X25519 handshake keys (for Briar compatibility)
	@Nullable
	private final PublicKey handshakePublicKey;
	@Nullable
	private final PrivateKey handshakePrivateKey;

	// Hybrid PQ handshake keys (for Zerion-to-Zerion)
	@Nullable
	private final PublicKey hybridHandshakePublicKey;
	@Nullable
	private final PrivateKey hybridHandshakePrivateKey;

	private final long created;

	/**
	 * Creates an identity with only classical handshake keys.
	 * Used for backward compatibility when loading legacy identities.
	 */
	public Identity(LocalAuthor localAuthor,
			@Nullable PublicKey handshakePublicKey,
			@Nullable PrivateKey handshakePrivateKey, long created) {
		this(localAuthor, handshakePublicKey, handshakePrivateKey,
				null, null, created);
	}

	/**
	 * Creates an identity with both classical and hybrid handshake keys.
	 * This is the preferred constructor for new identities.
	 */
	public Identity(LocalAuthor localAuthor,
			@Nullable PublicKey handshakePublicKey,
			@Nullable PrivateKey handshakePrivateKey,
			@Nullable PublicKey hybridHandshakePublicKey,
			@Nullable PrivateKey hybridHandshakePrivateKey,
			long created) {
		// Validate classical keys
		if (handshakePublicKey != null) {
			if (handshakePrivateKey == null)
				throw new IllegalArgumentException();
			if (!handshakePublicKey.getKeyType().equals(KEY_TYPE_AGREEMENT))
				throw new IllegalArgumentException();
		}
		if (handshakePrivateKey != null) {
			if (handshakePublicKey == null)
				throw new IllegalArgumentException();
			if (!handshakePrivateKey.getKeyType().equals(KEY_TYPE_AGREEMENT))
				throw new IllegalArgumentException();
		}
		// Validate hybrid keys
		if (hybridHandshakePublicKey != null) {
			if (hybridHandshakePrivateKey == null)
				throw new IllegalArgumentException();
			if (!hybridHandshakePublicKey.getKeyType()
					.equals(KEY_TYPE_HYBRID_AGREEMENT))
				throw new IllegalArgumentException();
		}
		if (hybridHandshakePrivateKey != null) {
			if (hybridHandshakePublicKey == null)
				throw new IllegalArgumentException();
			if (!hybridHandshakePrivateKey.getKeyType()
					.equals(KEY_TYPE_HYBRID_AGREEMENT))
				throw new IllegalArgumentException();
		}
		this.localAuthor = localAuthor;
		this.handshakePublicKey = handshakePublicKey;
		this.handshakePrivateKey = handshakePrivateKey;
		this.hybridHandshakePublicKey = hybridHandshakePublicKey;
		this.hybridHandshakePrivateKey = hybridHandshakePrivateKey;
		this.created = created;
	}

	/**
	 * Returns the ID of the user's pseudonym.
	 */
	public AuthorId getId() {
		return localAuthor.getId();
	}

	/**
	 * Returns the user's pseudonym.
	 */
	public LocalAuthor getLocalAuthor() {
		return localAuthor;
	}

	/**
	 * Returns true if the identity has a handshake key pair.
	 */
	public boolean hasHandshakeKeyPair() {
		return handshakePublicKey != null && handshakePrivateKey != null;
	}

	/**
	 * Returns the public key used for handshaking, or null if no key exists.
	 */
	@Nullable
	public PublicKey getHandshakePublicKey() {
		return handshakePublicKey;
	}

	/**
	 * Returns the private key used for handshaking, or null if no key exists.
	 */
	@Nullable
	public PrivateKey getHandshakePrivateKey() {
		return handshakePrivateKey;
	}

	/**
	 * Returns the time the identity was created, in milliseconds since the
	 * Unix epoch.
	 */
	public long getTimeCreated() {
		return created;
	}

	// ==================== Hybrid PQ Key Methods ====================

	/**
	 * Returns true if the identity has a hybrid (PQ) handshake key pair.
	 */
	public boolean hasHybridHandshakeKeyPair() {
		return hybridHandshakePublicKey != null &&
				hybridHandshakePrivateKey != null;
	}

	/**
	 * Returns the hybrid public key used for post-quantum handshaking,
	 * or null if no hybrid key exists.
	 */
	@Nullable
	public PublicKey getHybridHandshakePublicKey() {
		return hybridHandshakePublicKey;
	}

	/**
	 * Returns the hybrid private key used for post-quantum handshaking,
	 * or null if no hybrid key exists.
	 */
	@Nullable
	public PrivateKey getHybridHandshakePrivateKey() {
		return hybridHandshakePrivateKey;
	}

	/**
	 * Returns true if this identity supports post-quantum cryptography.
	 * An identity supports PQ if it has hybrid handshake keys.
	 */
	public boolean supportsPostQuantum() {
		return hasHybridHandshakeKeyPair();
	}
}

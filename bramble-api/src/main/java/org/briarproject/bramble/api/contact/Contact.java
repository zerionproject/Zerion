package org.briarproject.bramble.api.contact;

import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.briarproject.bramble.util.StringUtils.toUtf8;

/**
 * Represents an established contact.
 * <p>
 * The {@code postQuantum} flag indicates whether this contact was established
 * using hybrid post-quantum cryptography (X25519 + ML-KEM-768). Once a contact
 * is established with PQ, subsequent handshakes must also use PQ to prevent
 * downgrade attacks.
 */
@Immutable
@NotNullByDefault
public class Contact {

	private final ContactId id;
	private final Author author;
	private final AuthorId localAuthorId;
	@Nullable
	private final String alias;
	@Nullable
	private final PublicKey handshakePublicKey;
	private final boolean verified;
	private final boolean postQuantum;

	/**
	 * Creates a contact with classical (non-PQ) cryptography.
	 * For backward compatibility with existing code.
	 */
	public Contact(ContactId id, Author author, AuthorId localAuthorId,
			@Nullable String alias, @Nullable PublicKey handshakePublicKey,
			boolean verified) {
		this(id, author, localAuthorId, alias, handshakePublicKey, verified, false);
	}

	/**
	 * Creates a contact with the specified security level.
	 *
	 * @param postQuantum true if established with hybrid PQ cryptography
	 */
	public Contact(ContactId id, Author author, AuthorId localAuthorId,
			@Nullable String alias, @Nullable PublicKey handshakePublicKey,
			boolean verified, boolean postQuantum) {
		if (alias != null) {
			int aliasLength = toUtf8(alias).length;
			if (aliasLength == 0 || aliasLength > MAX_AUTHOR_NAME_LENGTH)
				throw new IllegalArgumentException();
		}
		this.id = id;
		this.author = author;
		this.localAuthorId = localAuthorId;
		this.alias = alias;
		this.handshakePublicKey = handshakePublicKey;
		this.verified = verified;
		this.postQuantum = postQuantum;
	}

	public ContactId getId() {
		return id;
	}

	public Author getAuthor() {
		return author;
	}

	public AuthorId getLocalAuthorId() {
		return localAuthorId;
	}

	@Nullable
	public String getAlias() {
		return alias;
	}

	@Nullable
	public PublicKey getHandshakePublicKey() {
		return handshakePublicKey;
	}

	public boolean isVerified() {
		return verified;
	}

	/**
	 * Returns true if this contact was established using hybrid post-quantum
	 * cryptography (X25519 + ML-KEM-768).
	 * <p>
	 * Once a contact is established with PQ security, subsequent handshakes
	 * must also use PQ to prevent downgrade attacks.
	 */
	public boolean isPostQuantum() {
		return postQuantum;
	}

	/**
	 * Returns true if this contact uses classical (non-PQ) cryptography.
	 * These contacts are compatible with Briar.
	 */
	public boolean isClassical() {
		return !postQuantum;
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Contact && id.equals(((Contact) o).id);
	}
}

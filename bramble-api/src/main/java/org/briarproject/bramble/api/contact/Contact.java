package org.briarproject.bramble.api.contact;

import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.briarproject.bramble.util.StringUtils.toUtf8;


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
	private final boolean pcsEnabled;
	private final boolean mode3Capable;
	@Nullable
	private final byte[] mlDsaSigPublicKey;


	public Contact(ContactId id, Author author, AuthorId localAuthorId,
			@Nullable String alias, @Nullable PublicKey handshakePublicKey,
			boolean verified) {
		this(id, author, localAuthorId, alias, handshakePublicKey, verified,
				false, false, false, null);
	}


	public Contact(ContactId id, Author author, AuthorId localAuthorId,
			@Nullable String alias, @Nullable PublicKey handshakePublicKey,
			boolean verified, boolean postQuantum) {
		this(id, author, localAuthorId, alias, handshakePublicKey, verified,
				postQuantum, false, false, null);
	}


	public Contact(ContactId id, Author author, AuthorId localAuthorId,
			@Nullable String alias, @Nullable PublicKey handshakePublicKey,
			boolean verified, boolean postQuantum, boolean pcsEnabled) {
		this(id, author, localAuthorId, alias, handshakePublicKey, verified,
				postQuantum, pcsEnabled, false, null);
	}


	public Contact(ContactId id, Author author, AuthorId localAuthorId,
			@Nullable String alias, @Nullable PublicKey handshakePublicKey,
			boolean verified, boolean postQuantum, boolean pcsEnabled,
			boolean mode3Capable) {
		this(id, author, localAuthorId, alias, handshakePublicKey, verified,
				postQuantum, pcsEnabled, mode3Capable, null);
	}


	public Contact(ContactId id, Author author, AuthorId localAuthorId,
			@Nullable String alias, @Nullable PublicKey handshakePublicKey,
			boolean verified, boolean postQuantum, boolean pcsEnabled,
			boolean mode3Capable, @Nullable byte[] mlDsaSigPublicKey) {
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
		this.pcsEnabled = pcsEnabled;
		this.mode3Capable = mode3Capable;
		this.mlDsaSigPublicKey = mlDsaSigPublicKey;
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

	
	public boolean isPostQuantum() {
		return postQuantum;
	}

	
	public boolean isClassical() {
		return !postQuantum;
	}

	
	public boolean isPcsEnabled() {
		return pcsEnabled;
	}


	public boolean isMode3Capable() {
		return mode3Capable;
	}


	@Nullable
	public byte[] getMlDsaSigPublicKey() {
		return mlDsaSigPublicKey;
	}


	public boolean hasHybridSigCapability() {
		return mlDsaSigPublicKey != null;
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

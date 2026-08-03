package org.zerionproject.core.api.identity;

import org.zerionproject.core.api.Nameable;
import org.zerionproject.core.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.CryptoConstants.KEY_TYPE_SIGNATURE;
import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.zerionproject.core.util.StringUtils.toUtf8;

@Immutable
@NotNullByDefault
public class Author implements Nameable {

	public static final int FORMAT_VERSION = 1;

	private final AuthorId id;
	private final int formatVersion;
	private final String name;
	private final PublicKey publicKey;

	public Author(AuthorId id, int formatVersion, String name,
			PublicKey publicKey) {
		int nameLength = toUtf8(name).length;
		if (nameLength == 0 || nameLength > MAX_AUTHOR_NAME_LENGTH)
			throw new IllegalArgumentException();
		if (!publicKey.getKeyType().equals(KEY_TYPE_SIGNATURE))
			throw new IllegalArgumentException();
		this.id = id;
		this.formatVersion = formatVersion;
		this.name = name;
		this.publicKey = publicKey;
	}

	public AuthorId getId() {
		return id;
	}

	public int getFormatVersion() {
		return formatVersion;
	}

	@Override
	public String getName() {
		return name;
	}

	public PublicKey getPublicKey() {
		return publicKey;
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Author && id.equals(((Author) o).id);
	}
}

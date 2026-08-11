package org.zerionproject.core.api.identity;

import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.CryptoConstants.KEY_TYPE_AGREEMENT;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_AGREEMENT;

@Immutable
@NotNullByDefault
public class Identity {

	private final LocalAuthor localAuthor;
	@Nullable
	private final PublicKey handshakePublicKey;
	@Nullable
	private final PrivateKey handshakePrivateKey;
	@Nullable
	private final PublicKey hybridHandshakePublicKey;
	@Nullable
	private final PrivateKey hybridHandshakePrivateKey;
	@Nullable
	private final byte[] mlDsaPublicKey;
	@Nullable
	private final byte[] mlDsaPrivateKey;

	private final long created;

	public Identity(LocalAuthor localAuthor,
			@Nullable PublicKey handshakePublicKey,
			@Nullable PrivateKey handshakePrivateKey, long created) {
		this(localAuthor, handshakePublicKey, handshakePrivateKey,
				null, null, null, null, created);
	}

	public Identity(LocalAuthor localAuthor,
			@Nullable PublicKey handshakePublicKey,
			@Nullable PrivateKey handshakePrivateKey,
			@Nullable PublicKey hybridHandshakePublicKey,
			@Nullable PrivateKey hybridHandshakePrivateKey,
			long created) {
		this(localAuthor, handshakePublicKey, handshakePrivateKey,
				hybridHandshakePublicKey, hybridHandshakePrivateKey,
				null, null, created);
	}

	public Identity(LocalAuthor localAuthor,
			@Nullable PublicKey handshakePublicKey,
			@Nullable PrivateKey handshakePrivateKey,
			@Nullable PublicKey hybridHandshakePublicKey,
			@Nullable PrivateKey hybridHandshakePrivateKey,
			@Nullable byte[] mlDsaPublicKey,
			@Nullable byte[] mlDsaPrivateKey,
			long created) {
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
		if (mlDsaPublicKey != null && mlDsaPrivateKey == null)
			throw new IllegalArgumentException();
		if (mlDsaPrivateKey != null && mlDsaPublicKey == null)
			throw new IllegalArgumentException();
		this.localAuthor = localAuthor;
		this.handshakePublicKey = handshakePublicKey;
		this.handshakePrivateKey = handshakePrivateKey;
		this.hybridHandshakePublicKey = hybridHandshakePublicKey;
		this.hybridHandshakePrivateKey = hybridHandshakePrivateKey;
		this.mlDsaPublicKey = mlDsaPublicKey;
		this.mlDsaPrivateKey = mlDsaPrivateKey;
		this.created = created;
	}

	public AuthorId getId() {
		return localAuthor.getId();
	}

	public LocalAuthor getLocalAuthor() {
		return localAuthor;
	}

	public boolean hasHandshakeKeyPair() {
		return handshakePublicKey != null && handshakePrivateKey != null;
	}

	@Nullable
	public PublicKey getHandshakePublicKey() {
		return handshakePublicKey;
	}

	@Nullable
	public PrivateKey getHandshakePrivateKey() {
		return handshakePrivateKey;
	}

	public long getTimeCreated() {
		return created;
	}

	public boolean hasHybridHandshakeKeyPair() {
		return hybridHandshakePublicKey != null &&
				hybridHandshakePrivateKey != null;
	}

	@Nullable
	public PublicKey getHybridHandshakePublicKey() {
		return hybridHandshakePublicKey;
	}

	@Nullable
	public PrivateKey getHybridHandshakePrivateKey() {
		return hybridHandshakePrivateKey;
	}

	public boolean supportsPostQuantum() {
		return hasHybridHandshakeKeyPair();
	}

	public boolean hasMlDsaSigKeyPair() {
		return mlDsaPublicKey != null && mlDsaPrivateKey != null;
	}

	@Nullable
	public byte[] getMlDsaSigPublicKey() {
		return mlDsaPublicKey;
	}

	@Nullable
	public byte[] getMlDsaSigPrivateKey() {
		return mlDsaPrivateKey;
	}
}

package org.zerionproject.core.api.contact;

import org.zerionproject.core.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.contact.HandshakeLinkConstants.FORMAT_VERSION_CLASSICAL;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.FORMAT_VERSION_HYBRID;

@Immutable
@NotNullByDefault
public class PendingContact {

	private final PendingContactId id;
	private final PublicKey publicKey;
	private final String alias;
	private final long timestamp;
	private final int formatVersion;

	public PendingContact(PendingContactId id, PublicKey publicKey,
			String alias, long timestamp) {
		this(id, publicKey, alias, timestamp, FORMAT_VERSION_CLASSICAL);
	}

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

	public int getFormatVersion() {
		return formatVersion;
	}

	public boolean isPostQuantum() {
		return formatVersion == FORMAT_VERSION_HYBRID;
	}

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

package org.zerionproject.core.api.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.identity.AuthorId;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class ContactLink {

	public static final int KIND_ZERO_ID = 1;
	public static final int KIND_PERMANENT = 2;
	public static final int KIND_PER_ADD_BINDING = 3;

	public static final int STATE_ACTIVE = 0;
	public static final int STATE_RETIRED = 1;
	public static final int STATE_WITHDRAWN = 2;

	private final byte[] linkId;
	private final AuthorId localAuthorId;
	private final int kind, state;
	@Nullable
	private final String label;
	@Nullable
	private final byte[] hybridPublicKey, hybridPrivateKey, inboxKey;
	private final long createdAt, lastSharedAt, retiredAt, withdrawnAt;

	public ContactLink(byte[] linkId, AuthorId localAuthorId, int kind,
			int state, @Nullable String label,
			@Nullable byte[] hybridPublicKey,
			@Nullable byte[] hybridPrivateKey, @Nullable byte[] inboxKey,
			long createdAt, long lastSharedAt, long retiredAt,
			long withdrawnAt) {
		this.linkId = linkId;
		this.localAuthorId = localAuthorId;
		this.kind = kind;
		this.state = state;
		this.label = label;
		this.hybridPublicKey = hybridPublicKey;
		this.hybridPrivateKey = hybridPrivateKey;
		this.inboxKey = inboxKey;
		this.createdAt = createdAt;
		this.lastSharedAt = lastSharedAt;
		this.retiredAt = retiredAt;
		this.withdrawnAt = withdrawnAt;
	}

	public byte[] getLinkId() {
		return linkId;
	}

	public AuthorId getLocalAuthorId() {
		return localAuthorId;
	}

	public int getKind() {
		return kind;
	}

	public int getState() {
		return state;
	}

	@Nullable
	public String getLabel() {
		return label;
	}

	@Nullable
	public byte[] getHybridPublicKey() {
		return hybridPublicKey;
	}

	@Nullable
	public byte[] getHybridPrivateKey() {
		return hybridPrivateKey;
	}

	@Nullable
	public byte[] getInboxKey() {
		return inboxKey;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public long getLastSharedAt() {
		return lastSharedAt;
	}

	public long getRetiredAt() {
		return retiredAt;
	}

	public long getWithdrawnAt() {
		return withdrawnAt;
	}

	public boolean hasInbox() {
		return kind == KIND_ZERO_ID || kind == KIND_PERMANENT;
	}

	public ContactLink withLastSharedAt(long at) {
		return new ContactLink(linkId, localAuthorId, kind, state, label,
				hybridPublicKey, hybridPrivateKey, inboxKey, createdAt, at,
				retiredAt, withdrawnAt);
	}

	public ContactLink retired(long at) {
		return new ContactLink(linkId, localAuthorId, kind, STATE_RETIRED,
				label, hybridPublicKey, hybridPrivateKey, inboxKey, createdAt,
				lastSharedAt, at, withdrawnAt);
	}

	public ContactLink withdrawn(long at) {
		return new ContactLink(linkId, localAuthorId, kind, STATE_WITHDRAWN,
				label, hybridPublicKey, hybridPrivateKey, inboxKey, createdAt,
				lastSharedAt, retiredAt, at);
	}
}

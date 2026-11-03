package org.zerionproject.core.api.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.identity.AuthorId;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class OutgoingRequest {

	public static final int STATE_QUEUED = 0;
	public static final int STATE_DELIVERED = 1;
	public static final int STATE_ADDED = 2;
	public static final int STATE_NOT_DELIVERED = 3;
	public static final int STATE_NO_ANSWER = 4;
	public static final int STATE_CANCELLED = 5;
	public static final int STATE_WITHDRAWING = 6;
	/**
	 * The request was accepted and the pairing ran, but the receiver turned
	 * out to be someone who is already a contact and the add was refused;
	 * that contact's keys were left as they were.
	 */
	public static final int STATE_ALREADY_CONTACT = 7;

	private final byte[] requestId;
	private final AuthorId localAuthorId;
	private final String targetLink, alias, shownName;
	@Nullable
	private final String note;
	@Nullable
	private final byte[] oneTimePublicKey, oneTimePrivateKey;
	private final long createdAt;
	private final int state, attempts;
	private final long nextAttemptAt, deliveredAt, cancelledAt;
	@Nullable
	private final PendingContactId pendingContactId;

	public OutgoingRequest(byte[] requestId, AuthorId localAuthorId,
			String targetLink, String alias, String shownName,
			@Nullable String note, @Nullable byte[] oneTimePublicKey,
			@Nullable byte[] oneTimePrivateKey, long createdAt, int state,
			int attempts, long nextAttemptAt, long deliveredAt,
			@Nullable PendingContactId pendingContactId, long cancelledAt) {
		this.requestId = requestId;
		this.localAuthorId = localAuthorId;
		this.targetLink = targetLink;
		this.alias = alias;
		this.shownName = shownName;
		this.note = note;
		this.oneTimePublicKey = oneTimePublicKey;
		this.oneTimePrivateKey = oneTimePrivateKey;
		this.createdAt = createdAt;
		this.state = state;
		this.attempts = attempts;
		this.nextAttemptAt = nextAttemptAt;
		this.deliveredAt = deliveredAt;
		this.pendingContactId = pendingContactId;
		this.cancelledAt = cancelledAt;
	}

	public byte[] getRequestId() {
		return requestId;
	}

	public AuthorId getLocalAuthorId() {
		return localAuthorId;
	}

	public String getTargetLink() {
		return targetLink;
	}

	public String getAlias() {
		return alias;
	}

	public String getShownName() {
		return shownName;
	}

	@Nullable
	public String getNote() {
		return note;
	}

	@Nullable
	public byte[] getOneTimePublicKey() {
		return oneTimePublicKey;
	}

	@Nullable
	public byte[] getOneTimePrivateKey() {
		return oneTimePrivateKey;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public int getState() {
		return state;
	}

	public int getAttempts() {
		return attempts;
	}

	public long getNextAttemptAt() {
		return nextAttemptAt;
	}

	public long getDeliveredAt() {
		return deliveredAt;
	}

	@Nullable
	public PendingContactId getPendingContactId() {
		return pendingContactId;
	}

	public long getCancelledAt() {
		return cancelledAt;
	}

	public boolean isOpen() {
		return state == STATE_QUEUED || state == STATE_DELIVERED
				|| state == STATE_WITHDRAWING;
	}

	public OutgoingRequest scheduled(int attempts, long nextAttemptAt) {
		return new OutgoingRequest(requestId, localAuthorId, targetLink,
				alias, shownName, note, oneTimePublicKey, oneTimePrivateKey,
				createdAt, state, attempts, nextAttemptAt, deliveredAt,
				pendingContactId, cancelledAt);
	}

	public OutgoingRequest delivered(long at, @Nullable PendingContactId p) {
		return new OutgoingRequest(requestId, localAuthorId, targetLink,
				alias, shownName, note, oneTimePublicKey, oneTimePrivateKey,
				createdAt, STATE_DELIVERED, attempts, nextAttemptAt, at, p,
				cancelledAt);
	}

	public OutgoingRequest ended(int newState) {
		return new OutgoingRequest(requestId, localAuthorId, targetLink,
				alias, shownName, note, null, null, createdAt, newState,
				attempts, nextAttemptAt, deliveredAt, null, cancelledAt);
	}

	public OutgoingRequest withdrawing(long at) {
		return new OutgoingRequest(requestId, localAuthorId, targetLink,
				alias, shownName, note, null, null, createdAt,
				STATE_WITHDRAWING, 0, at, deliveredAt, null, at);
	}
}

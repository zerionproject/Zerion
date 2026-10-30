package org.zerionproject.core.api.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.PendingContactId;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class OutgoingRequestInfo {

	private final byte[] requestId;
	private final String targetLink, alias, shownName;
	@Nullable
	private final String note;
	private final int state;
	private final boolean withdrawing, inboxBusy;
	private final long createdAt, deliveredAt;
	@Nullable
	private final PendingContactId pendingContactId;

	public OutgoingRequestInfo(OutgoingRequest r) {
		this(r, false);
	}

	public OutgoingRequestInfo(OutgoingRequest r, boolean inboxBusy) {
		this.inboxBusy = inboxBusy;
		requestId = r.getRequestId().clone();
		targetLink = r.getTargetLink();
		alias = r.getAlias();
		shownName = r.getShownName();
		note = r.getNote();
		int s = r.getState();
		withdrawing = s == OutgoingRequest.STATE_WITHDRAWING;
		state = s == OutgoingRequest.STATE_WITHDRAWING
				? OutgoingRequest.STATE_CANCELLED : s;
		createdAt = r.getCreatedAt();
		deliveredAt = r.getDeliveredAt();
		pendingContactId = r.getPendingContactId();
	}

	public byte[] getRequestId() {
		return requestId.clone();
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

	public int getState() {
		return state;
	}

	public boolean isInboxBusy() {
		return inboxBusy;
	}

	public boolean isWithdrawing() {
		return withdrawing;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public long getDeliveredAt() {
		return deliveredAt;
	}

	@Nullable
	public PendingContactId getPendingContactId() {
		return pendingContactId;
	}
}

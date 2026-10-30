package org.zerionproject.core.api.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.PendingContactId;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.contact.request.ContactRequestConstants.CLOCK_DIFFERENCE_NOTICE_MS;

@Immutable
@NotNullByDefault
public final class IncomingRequestInfo {

	private final byte[] requestId, linkId;
	private final int linkKind;
	@Nullable
	private final String linkLabel, name, note;
	private final long receivedAt, acceptedAt;
	private final int state;
	private final boolean clockDiffers;
	@Nullable
	private final PendingContactId pendingContactId;

	public IncomingRequestInfo(IncomingRequest r, int linkKind,
			@Nullable String linkLabel) {
		requestId = r.getRequestId().clone();
		linkId = r.getLinkId().clone();
		this.linkKind = linkKind;
		this.linkLabel = linkLabel;
		name = r.getName();
		note = r.getNote();
		receivedAt = r.getReceivedAt();
		acceptedAt = r.getAcceptedAt();
		state = r.getState();
		clockDiffers = Math.abs(r.getSenderTimestamp() - r.getReceivedAt())
				> CLOCK_DIFFERENCE_NOTICE_MS;
		pendingContactId = r.getPendingContactId();
	}

	public byte[] getRequestId() {
		return requestId.clone();
	}

	public byte[] getLinkId() {
		return linkId.clone();
	}

	public int getLinkKind() {
		return linkKind;
	}

	@Nullable
	public String getLinkLabel() {
		return linkLabel;
	}

	@Nullable
	public String getName() {
		return name;
	}

	@Nullable
	public String getNote() {
		return note;
	}

	public long getReceivedAt() {
		return receivedAt;
	}

	public long getAcceptedAt() {
		return acceptedAt;
	}

	public int getState() {
		return state;
	}

	public boolean isClockDifferent() {
		return clockDiffers;
	}

	@Nullable
	public PendingContactId getPendingContactId() {
		return pendingContactId;
	}
}

package org.zerionproject.core.api.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.PendingContactId;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class IncomingRequest {

	public static final int STATE_RECEIVED = 0;
	public static final int STATE_OPENED = 1;
	public static final int STATE_ACCEPTED = 2;
	public static final int STATE_WITHDRAWN = 3;
	/**
	 * The request was accepted and the pairing ran, but the sender turned
	 * out to be someone who is already a contact and the add was refused;
	 * that contact's keys were left as they were.
	 */
	public static final int STATE_ALREADY_CONTACT = 4;

	private final byte[] requestId, linkId, oneTimeLink, senderTag;
	@Nullable
	private final String name, note;
	private final long senderTimestamp, receivedAt, acceptedAt;
	private final int state;
	@Nullable
	private final PendingContactId pendingContactId;

	public IncomingRequest(byte[] requestId, byte[] linkId,
			byte[] oneTimeLink, @Nullable String name, @Nullable String note,
			byte[] senderTag, long senderTimestamp, long receivedAt,
			int state, long acceptedAt,
			@Nullable PendingContactId pendingContactId) {
		this.requestId = requestId;
		this.linkId = linkId;
		this.oneTimeLink = oneTimeLink;
		this.name = name;
		this.note = note;
		this.senderTag = senderTag;
		this.senderTimestamp = senderTimestamp;
		this.receivedAt = receivedAt;
		this.state = state;
		this.acceptedAt = acceptedAt;
		this.pendingContactId = pendingContactId;
	}

	public byte[] getRequestId() {
		return requestId;
	}

	public byte[] getLinkId() {
		return linkId;
	}

	public byte[] getOneTimeLink() {
		return oneTimeLink;
	}

	@Nullable
	public String getName() {
		return name;
	}

	@Nullable
	public String getNote() {
		return note;
	}

	public byte[] getSenderTag() {
		return senderTag;
	}

	public long getSenderTimestamp() {
		return senderTimestamp;
	}

	public long getReceivedAt() {
		return receivedAt;
	}

	public int getState() {
		return state;
	}

	public long getAcceptedAt() {
		return acceptedAt;
	}

	@Nullable
	public PendingContactId getPendingContactId() {
		return pendingContactId;
	}

	public boolean isUndecided() {
		return state == STATE_RECEIVED || state == STATE_OPENED;
	}

	public IncomingRequest opened() {
		return new IncomingRequest(requestId, linkId, oneTimeLink, name, note,
				senderTag, senderTimestamp, receivedAt, STATE_OPENED,
				acceptedAt, pendingContactId);
	}

	public IncomingRequest accepted(long at, PendingContactId p) {
		return new IncomingRequest(requestId, linkId, oneTimeLink, null, null,
				senderTag, senderTimestamp, receivedAt, STATE_ACCEPTED, at, p);
	}

	/**
	 * @param alias the name the user gave the contact when accepting, kept
	 * so the refused request can still be recognised; the note stays deleted.
	 */
	public IncomingRequest alreadyContact(@Nullable String alias) {
		return new IncomingRequest(requestId, linkId, oneTimeLink, alias, null,
				senderTag, senderTimestamp, receivedAt, STATE_ALREADY_CONTACT,
				acceptedAt, null);
	}

	public IncomingRequest withdrawn() {
		return new IncomingRequest(requestId, linkId, oneTimeLink, null, null,
				senderTag, senderTimestamp, receivedAt, STATE_WITHDRAWN,
				acceptedAt, null);
	}
}

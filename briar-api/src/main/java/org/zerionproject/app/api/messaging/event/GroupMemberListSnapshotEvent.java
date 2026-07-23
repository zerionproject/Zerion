package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupMemberListSnapshotEvent extends Event {

	private final ContactId contactId;
	private final byte[] groupId;
	private final long epoch;
	private final long timestamp;
	private final byte[] memberCanonical;
	private final byte[] recordSig;
	private final byte[] signedInput;

	public GroupMemberListSnapshotEvent(ContactId contactId, byte[] groupId,
			long epoch, long timestamp, byte[] memberCanonical,
			byte[] recordSig, byte[] signedInput) {
		this.contactId = contactId;
		this.groupId = groupId;
		this.epoch = epoch;
		this.timestamp = timestamp;
		this.memberCanonical = memberCanonical;
		this.recordSig = recordSig;
		this.signedInput = signedInput;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public long getEpoch() {
		return epoch;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public byte[] getMemberCanonical() {
		return memberCanonical;
	}

	public byte[] getRecordSig() {
		return recordSig;
	}

	public byte[] getSignedInput() {
		return signedInput;
	}
}

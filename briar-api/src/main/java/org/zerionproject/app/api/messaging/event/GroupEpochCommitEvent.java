package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupEpochCommitEvent extends Event {

	private final ContactId contactId;
	private final byte[] groupId;
	private final long fromEpoch;
	private final long toEpoch;
	private final byte[] pqSeed;
	private final byte[] recordSig;
	private final byte[] signedInput;
	private final long timestamp;

	public GroupEpochCommitEvent(ContactId contactId, byte[] groupId,
			long fromEpoch, long toEpoch, byte[] pqSeed,
			byte[] recordSig, byte[] signedInput, long timestamp) {
		this.contactId = contactId;
		this.groupId = groupId;
		this.fromEpoch = fromEpoch;
		this.toEpoch = toEpoch;
		this.pqSeed = pqSeed;
		this.recordSig = recordSig;
		this.signedInput = signedInput;
		this.timestamp = timestamp;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public long getFromEpoch() {
		return fromEpoch;
	}

	public long getToEpoch() {
		return toEpoch;
	}

	public byte[] getPqSeed() {
		return pqSeed;
	}

	public byte[] getRecordSig() {
		return recordSig;
	}

	public byte[] getSignedInput() {
		return signedInput;
	}

	public long getTimestamp() {
		return timestamp;
	}
}

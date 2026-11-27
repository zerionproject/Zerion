package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupPinReceivedEvent extends Event {

	private final ContactId contactId;
	private final byte[] groupId;
	@Nullable
	private final byte[] targetPostId;
	private final long timestamp;
	private final byte[] recordSig;

	public GroupPinReceivedEvent(ContactId contactId, byte[] groupId,
			@Nullable byte[] targetPostId, long timestamp, byte[] recordSig) {
		this.contactId = contactId;
		this.groupId = groupId;
		this.targetPostId = targetPostId;
		this.timestamp = timestamp;
		this.recordSig = recordSig;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	@Nullable
	public byte[] getTargetPostId() {
		return targetPostId;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public byte[] getRecordSig() {
		return recordSig;
	}
}

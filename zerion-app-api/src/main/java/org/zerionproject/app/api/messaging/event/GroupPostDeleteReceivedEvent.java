package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupPostDeleteReceivedEvent extends Event {

	private final ContactId contactId;
	private final MessageId messageId;
	private final byte[] groupId;
	private final long epoch;
	private final byte[] senderPubKey;
	private final byte[] targetPostId;
	private final long timestamp;
	private final byte[] recordSig;

	public GroupPostDeleteReceivedEvent(ContactId contactId,
			MessageId messageId, byte[] groupId, long epoch,
			byte[] senderPubKey, byte[] targetPostId, long timestamp,
			byte[] recordSig) {
		this.contactId = contactId;
		this.messageId = messageId;
		this.groupId = groupId;
		this.epoch = epoch;
		this.senderPubKey = senderPubKey;
		this.targetPostId = targetPostId;
		this.timestamp = timestamp;
		this.recordSig = recordSig;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public MessageId getMessageId() {
		return messageId;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public long getEpoch() {
		return epoch;
	}

	public byte[] getSenderPubKey() {
		return senderPubKey;
	}

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

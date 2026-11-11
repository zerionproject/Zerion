package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrRelayRecordEvent extends Event {

	private final ContactId contactId;
	private final MessageId messageId;
	private final int messageType;
	private final BdfList record;
	private final long timestamp;

	public GroupTrRelayRecordEvent(ContactId contactId, MessageId messageId,
			int messageType, BdfList record, long timestamp) {
		this.contactId = contactId;
		this.messageId = messageId;
		this.messageType = messageType;
		this.record = record;
		this.timestamp = timestamp;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public MessageId getMessageId() {
		return messageId;
	}

	public int getMessageType() {
		return messageType;
	}

	public BdfList getRecord() {
		return record;
	}

	public long getTimestamp() {
		return timestamp;
	}
}

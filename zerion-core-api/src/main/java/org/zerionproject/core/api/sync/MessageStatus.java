package org.zerionproject.core.api.sync;

import org.zerionproject.core.api.contact.ContactId;

public class MessageStatus {

	private final MessageId messageId;
	private final ContactId contactId;
	private final boolean sent, seen;

	public MessageStatus(MessageId messageId, ContactId contactId,
			boolean sent, boolean seen) {
		this.messageId = messageId;
		this.contactId = contactId;
		this.sent = sent;
		this.seen = seen;
	}

	public MessageId getMessageId() {
		return messageId;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public boolean isSent() {
		return sent;
	}

	public boolean isSeen() {
		return seen;
	}
}

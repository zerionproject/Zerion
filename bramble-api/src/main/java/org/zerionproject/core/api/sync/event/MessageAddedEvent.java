package org.zerionproject.core.api.sync.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class MessageAddedEvent extends Event {

	private final Message message;
	@Nullable
	private final ContactId contactId;

	public MessageAddedEvent(Message message, @Nullable ContactId contactId) {
		this.message = message;
		this.contactId = contactId;
	}

	public Message getMessage() {
		return message;
	}

	public GroupId getGroupId() {
		return message.getGroupId();
	}

	@Nullable
	public ContactId getContactId() {
		return contactId;
	}
}

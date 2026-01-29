package org.briarproject.bramble.api.sync.event;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
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

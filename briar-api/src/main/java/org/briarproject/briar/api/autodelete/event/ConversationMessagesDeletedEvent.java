package org.briarproject.briar.api.autodelete.event;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ConversationMessagesDeletedEvent extends Event {

	private final ContactId contactId;
	private final Collection<MessageId> messageIds;

	public ConversationMessagesDeletedEvent(ContactId contactId,
			Collection<MessageId> messageIds) {
		this.contactId = contactId;
		this.messageIds = messageIds;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public Collection<MessageId> getMessageIds() {
		return messageIds;
	}
}

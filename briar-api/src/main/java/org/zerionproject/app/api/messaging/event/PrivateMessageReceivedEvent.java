package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.app.api.conversation.event.ConversationMessageReceivedEvent;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class PrivateMessageReceivedEvent
		extends ConversationMessageReceivedEvent<PrivateMessageHeader> {

	public PrivateMessageReceivedEvent(PrivateMessageHeader messageHeader,
			ContactId contactId) {
		super(messageHeader, contactId);
	}

}

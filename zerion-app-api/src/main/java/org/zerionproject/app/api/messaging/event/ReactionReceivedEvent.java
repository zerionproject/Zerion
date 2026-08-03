package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ReactionReceivedEvent extends Event {

	private final ContactId contactId;
	private final MessageId targetMessageId;
	private final String emoji;
	private final boolean local;

	public ReactionReceivedEvent(ContactId contactId,
			MessageId targetMessageId, String emoji, boolean local) {
		this.contactId = contactId;
		this.targetMessageId = targetMessageId;
		this.emoji = emoji;
		this.local = local;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public MessageId getTargetMessageId() {
		return targetMessageId;
	}

	public String getEmoji() {
		return emoji;
	}

	public boolean isLocal() {
		return local;
	}
}

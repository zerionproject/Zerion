package org.zerionproject.core.api.sync.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.Group.Visibility;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class MessageSharedEvent extends Event {

	private final MessageId messageId;
	private final GroupId groupId;
	private final Map<ContactId, Boolean> groupVisibility;

	public MessageSharedEvent(MessageId message, GroupId groupId,
			Map<ContactId, Boolean> groupVisibility) {
		this.messageId = message;
		this.groupId = groupId;
		this.groupVisibility = groupVisibility;
	}

	public MessageId getMessageId() {
		return messageId;
	}

	public GroupId getGroupId() {
		return groupId;
	}

	public Map<ContactId, Boolean> getGroupVisibility() {
		return groupVisibility;
	}
}

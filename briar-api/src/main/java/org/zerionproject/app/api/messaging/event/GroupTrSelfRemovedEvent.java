package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrSelfRemovedEvent extends Event {

	private final byte[] groupId;
	private final String groupName;
	private final ContactId removedBy;

	public GroupTrSelfRemovedEvent(byte[] groupId, String groupName,
			ContactId removedBy) {
		this.groupId = groupId;
		this.groupName = groupName;
		this.removedBy = removedBy;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public String getGroupName() {
		return groupName;
	}

	public ContactId getRemovedBy() {
		return removedBy;
	}
}

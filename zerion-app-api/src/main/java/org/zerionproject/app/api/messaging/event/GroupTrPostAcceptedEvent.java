package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrPostAcceptedEvent extends Event {

	private final byte[] groupId;
	private final boolean local;

	public GroupTrPostAcceptedEvent(byte[] groupId, boolean local) {
		this.groupId = groupId;
		this.local = local;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public boolean isLocal() {
		return local;
	}
}

package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrLocalStateChangedEvent extends Event {

	public enum Kind {
		CREATED,
		MEMBER_ADDED,
		MEMBER_REMOVED,
		REMOVED,
		UPDATED
	}

	private final byte[] groupId;
	private final Kind kind;

	public GroupTrLocalStateChangedEvent(byte[] groupId, Kind kind) {
		this.groupId = groupId;
		this.kind = kind;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public Kind getKind() {
		return kind;
	}
}

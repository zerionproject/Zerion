package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrOwnPostStateChangedEvent extends Event {

	private final byte[] groupId;
	private final List<byte[]> postIds;

	public GroupTrOwnPostStateChangedEvent(byte[] groupId,
			List<byte[]> postIds) {
		this.groupId = groupId;
		this.postIds = Collections.unmodifiableList(new ArrayList<>(postIds));
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public List<byte[]> getPostIds() {
		return postIds;
	}
}

package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrPostsRemovedEvent extends Event {

	private final byte[] groupId;
	private final List<byte[]> postIds;
	private final int unreadRemoved;

	public GroupTrPostsRemovedEvent(byte[] groupId, List<byte[]> postIds,
			int unreadRemoved) {
		this.groupId = groupId;
		this.postIds = postIds;
		this.unreadRemoved = unreadRemoved;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public List<byte[]> getPostIds() {
		return postIds;
	}

	public int getUnreadRemoved() {
		return unreadRemoved;
	}
}

package org.zerionproject.app.api.privategroup;

import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.client.PostHeader;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupMessageHeader extends PostHeader {

	private final GroupId groupId;

	public GroupMessageHeader(GroupId groupId, MessageId id,
			@Nullable MessageId parentId, long timestamp,
			Author author, AuthorInfo authorInfo, boolean read) {
		super(id, parentId, timestamp, author, authorInfo, read);
		this.groupId = groupId;
	}

	public GroupId getGroupId() {
		return groupId;
	}

}

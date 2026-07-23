package org.zerionproject.app.api.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class GroupTrPendingInvite {

	private final byte[] groupId;
	private final String groupName;
	private final String creatorName;
	private final long timestamp;

	public GroupTrPendingInvite(byte[] groupId, String groupName,
			String creatorName, long timestamp) {
		this.groupId = groupId;
		this.groupName = groupName;
		this.creatorName = creatorName;
		this.timestamp = timestamp;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public String getGroupName() {
		return groupName;
	}

	public String getCreatorName() {
		return creatorName;
	}

	public long getTimestamp() {
		return timestamp;
	}
}

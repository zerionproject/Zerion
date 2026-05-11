package org.briarproject.briar.api.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;
import java.util.List;

@NotNullByDefault
public class GroupTrState {

	private final byte[] groupId;
	private final String name;
	private final byte[] salt;
	private final byte[] creatorPubKey;
	private final String creatorName;
	private final long created;
	private long epoch;
	private boolean dissolved;
	private List<GroupTrMember> members;

	public GroupTrState(byte[] groupId, String name, byte[] salt,
			byte[] creatorPubKey, String creatorName, long created,
			long epoch, boolean dissolved, List<GroupTrMember> members) {
		this.groupId = groupId;
		this.name = name;
		this.salt = salt;
		this.creatorPubKey = creatorPubKey;
		this.creatorName = creatorName;
		this.created = created;
		this.epoch = epoch;
		this.dissolved = dissolved;
		this.members = members;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public String getName() {
		return name;
	}

	public byte[] getSalt() {
		return salt;
	}

	public byte[] getCreatorPubKey() {
		return creatorPubKey;
	}

	public String getCreatorName() {
		return creatorName;
	}

	public long getCreated() {
		return created;
	}

	public long getEpoch() {
		return epoch;
	}

	public void setEpoch(long epoch) {
		this.epoch = epoch;
	}

	public boolean isDissolved() {
		return dissolved;
	}

	public void setDissolved(boolean dissolved) {
		this.dissolved = dissolved;
	}

	public List<GroupTrMember> getMembers() {
		return Collections.unmodifiableList(members);
	}

	public void setMembers(List<GroupTrMember> members) {
		this.members = members;
	}
}

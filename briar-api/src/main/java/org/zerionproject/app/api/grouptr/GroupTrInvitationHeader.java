package org.zerionproject.app.api.grouptr;

import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.conversation.ConversationMessageVisitor;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;

@Immutable
@NotNullByDefault
public class GroupTrInvitationHeader extends ConversationMessageHeader {

	private final GroupId groupTrGroupId;
	private final String groupName;
	private final byte[] salt;
	private final String creatorName;
	private final byte[] creatorPubKey;
	private final long inviteTimestamp;

	public GroupTrInvitationHeader(MessageId id, GroupId contactGroupId,
			long timestamp, boolean local, boolean read, boolean sent,
			boolean seen, GroupId groupTrGroupId, String groupName,
			byte[] salt, String creatorName, byte[] creatorPubKey,
			long inviteTimestamp) {
		super(id, contactGroupId, timestamp, local, read, sent, seen,
				NO_AUTO_DELETE_TIMER);
		this.groupTrGroupId = groupTrGroupId;
		this.groupName = groupName;
		this.salt = salt;
		this.creatorName = creatorName;
		this.creatorPubKey = creatorPubKey;
		this.inviteTimestamp = inviteTimestamp;
	}

	public GroupId getGroupTrGroupId() {
		return groupTrGroupId;
	}

	public String getGroupName() {
		return groupName;
	}

	public byte[] getSalt() {
		return salt;
	}

	public String getCreatorName() {
		return creatorName;
	}

	public byte[] getCreatorPubKey() {
		return creatorPubKey;
	}

	public long getInviteTimestamp() {
		return inviteTimestamp;
	}

	@Override
	public <T> T accept(ConversationMessageVisitor<T> v) {
		return v.visitGroupTrInvitation(this);
	}
}

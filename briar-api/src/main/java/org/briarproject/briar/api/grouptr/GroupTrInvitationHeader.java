package org.briarproject.briar.api.grouptr;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.conversation.ConversationMessageVisitor;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;

@Immutable
@NotNullByDefault
public class GroupTrInvitationHeader extends ConversationMessageHeader {

	private final GroupId groupTrGroupId;
	private final String groupName;

	public GroupTrInvitationHeader(MessageId id, GroupId contactGroupId,
			long timestamp, boolean local, boolean read, boolean sent,
			boolean seen, GroupId groupTrGroupId, String groupName) {
		super(id, contactGroupId, timestamp, local, read, sent, seen,
				NO_AUTO_DELETE_TIMER);
		this.groupTrGroupId = groupTrGroupId;
		this.groupName = groupName;
	}

	public GroupId getGroupTrGroupId() {
		return groupTrGroupId;
	}

	public String getGroupName() {
		return groupName;
	}

	@Override
	public <T> T accept(ConversationMessageVisitor<T> v) {
		return v.visitGroupTrInvitation(this);
	}
}

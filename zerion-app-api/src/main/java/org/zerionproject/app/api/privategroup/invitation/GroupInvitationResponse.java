package org.zerionproject.app.api.privategroup.invitation;

import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.conversation.ConversationMessageVisitor;
import org.zerionproject.app.api.sharing.InvitationResponse;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupInvitationResponse extends InvitationResponse {

	public GroupInvitationResponse(MessageId id, GroupId groupId, long time,
			boolean local, boolean read, boolean sent, boolean seen,
			SessionId sessionId, boolean accept, GroupId shareableId,
			long autoDeleteTimer, boolean isAutoDecline) {
		super(id, groupId, time, local, read, sent, seen, sessionId,
				accept, shareableId, autoDeleteTimer, isAutoDecline);
	}

	@Override
	public <T> T accept(ConversationMessageVisitor<T> v) {
		return v.visitGroupInvitationResponse(this);
	}
}

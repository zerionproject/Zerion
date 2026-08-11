package org.zerionproject.app.api.privategroup.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.app.api.conversation.event.ConversationMessageReceivedEvent;
import org.zerionproject.app.api.privategroup.invitation.GroupInvitationRequest;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupInvitationRequestReceivedEvent extends
		ConversationMessageReceivedEvent<GroupInvitationRequest> {

	public GroupInvitationRequestReceivedEvent(GroupInvitationRequest request,
			ContactId contactId) {
		super(request, contactId);
	}

}

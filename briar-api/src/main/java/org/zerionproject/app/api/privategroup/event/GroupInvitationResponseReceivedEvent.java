package org.zerionproject.app.api.privategroup.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.app.api.conversation.event.ConversationMessageReceivedEvent;
import org.zerionproject.app.api.privategroup.invitation.GroupInvitationResponse;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupInvitationResponseReceivedEvent
		extends ConversationMessageReceivedEvent<GroupInvitationResponse> {

	public GroupInvitationResponseReceivedEvent(
			GroupInvitationResponse response, ContactId contactId) {
		super(response, contactId);
	}
}

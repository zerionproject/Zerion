package org.zerionproject.app.api.conversation;

import org.zerionproject.app.api.grouptr.GroupTrInvitationHeader;
import org.zerionproject.app.api.introduction.IntroductionRequest;
import org.zerionproject.app.api.introduction.IntroductionResponse;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.app.api.privategroup.invitation.GroupInvitationRequest;
import org.zerionproject.app.api.privategroup.invitation.GroupInvitationResponse;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ConversationMessageVisitor<T> {

	T visitPrivateMessageHeader(PrivateMessageHeader h);

	T visitGroupInvitationRequest(GroupInvitationRequest r);

	T visitGroupInvitationResponse(GroupInvitationResponse r);

	T visitIntroductionRequest(IntroductionRequest r);

	T visitIntroductionResponse(IntroductionResponse r);

	T visitGroupTrInvitation(GroupTrInvitationHeader h);
}

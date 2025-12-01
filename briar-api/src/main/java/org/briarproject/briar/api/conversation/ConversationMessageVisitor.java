package org.briarproject.briar.api.conversation;

import org.briarproject.briar.api.introduction.IntroductionRequest;
import org.briarproject.briar.api.introduction.IntroductionResponse;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationRequest;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationResponse;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ConversationMessageVisitor<T> {

	T visitPrivateMessageHeader(PrivateMessageHeader h);

	T visitGroupInvitationRequest(GroupInvitationRequest r);

	T visitGroupInvitationResponse(GroupInvitationResponse r);

	T visitIntroductionRequest(IntroductionRequest r);

	T visitIntroductionResponse(IntroductionResponse r);
}

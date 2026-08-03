package org.zerionproject.app.api.introduction.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.app.api.conversation.event.ConversationMessageReceivedEvent;
import org.zerionproject.app.api.introduction.IntroductionRequest;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class IntroductionRequestReceivedEvent
		extends ConversationMessageReceivedEvent<IntroductionRequest> {

	public IntroductionRequestReceivedEvent(
			IntroductionRequest introductionRequest, ContactId contactId) {
		super(introductionRequest, contactId);
	}

}

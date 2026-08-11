package org.zerionproject.app.api.introduction.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.app.api.conversation.event.ConversationMessageReceivedEvent;
import org.zerionproject.app.api.introduction.IntroductionResponse;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class IntroductionResponseReceivedEvent extends
		ConversationMessageReceivedEvent<IntroductionResponse> {

	public IntroductionResponseReceivedEvent(
			IntroductionResponse introductionResponse, ContactId contactId) {
		super(introductionResponse, contactId);
	}

}

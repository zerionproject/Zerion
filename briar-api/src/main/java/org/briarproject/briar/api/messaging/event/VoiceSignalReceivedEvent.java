package org.briarproject.briar.api.messaging.event;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.briar.api.messaging.VoiceSignalHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * An event that is broadcast when a voice call signal is received.
 * This event is separate from ConversationMessageReceivedEvent because
 * voice signals should NOT appear in the conversation UI.
 *
 * VoiceCallService listens for this event and handles call state changes.
 * ConversationActivity does NOT listen for this event.
 */
@Immutable
@NotNullByDefault
public class VoiceSignalReceivedEvent extends Event {

	private final VoiceSignalHeader signalHeader;
	private final ContactId contactId;

	public VoiceSignalReceivedEvent(VoiceSignalHeader signalHeader,
			ContactId contactId) {
		this.signalHeader = signalHeader;
		this.contactId = contactId;
	}

	public VoiceSignalHeader getSignalHeader() {
		return signalHeader;
	}

	public ContactId getContactId() {
		return contactId;
	}
}

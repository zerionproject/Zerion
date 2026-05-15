package org.briarproject.briar.api.messaging.event;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.briar.api.messaging.VoiceSignalHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

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

package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;

import javax.annotation.concurrent.Immutable;

@Immutable
public class MeshLinkKeysReceivedEvent extends Event {

	private final ContactId contactId;
	private final byte[] advertKey;
	private final byte[] pairSecret;

	public MeshLinkKeysReceivedEvent(ContactId contactId, byte[] advertKey,
			byte[] pairSecret) {
		this.contactId = contactId;
		this.advertKey = advertKey;
		this.pairSecret = pairSecret;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public byte[] getAdvertKey() {
		return advertKey;
	}

	public byte[] getPairSecret() {
		return pairSecret;
	}
}

package org.zerionproject.app.api.grouptr;

import org.zerionproject.core.api.contact.ContactId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrSentInvite {

	private final ContactId contactId;
	private final String contactName;
	private final long timestamp;

	public GroupTrSentInvite(ContactId contactId, String contactName,
			long timestamp) {
		this.contactId = contactId;
		this.contactName = contactName;
		this.timestamp = timestamp;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public String getContactName() {
		return contactName;
	}

	public long getTimestamp() {
		return timestamp;
	}
}

package org.zerionproject.core.api.contact.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ContactAliasChangedEvent extends Event {

	private final ContactId contactId;
	@Nullable
	private final String alias;

	public ContactAliasChangedEvent(ContactId contactId,
			@Nullable String alias) {
		this.contactId = contactId;
		this.alias = alias;
	}

	public ContactId getContactId() {
		return contactId;
	}

	@Nullable
	public String getAlias() {
		return alias;
	}
}

package org.zerionproject.app.api.avatar.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class AvatarUpdatedEvent extends Event {

	private final ContactId contactId;
	private final AttachmentHeader attachmentHeader;

	public AvatarUpdatedEvent(ContactId contactId,
			AttachmentHeader attachmentHeader) {
		this.contactId = contactId;
		this.attachmentHeader = attachmentHeader;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public AttachmentHeader getAttachmentHeader() {
		return attachmentHeader;
	}
}

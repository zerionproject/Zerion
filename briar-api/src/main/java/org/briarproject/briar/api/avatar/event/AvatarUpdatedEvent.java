package org.briarproject.briar.api.avatar.event;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.briar.api.attachment.AttachmentHeader;
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

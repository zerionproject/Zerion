package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrInviteOfferReceivedEvent extends Event {

	private final ContactId contactId;
	private final MessageId messageId;
	private final byte[] grouptrGroupId;
	private final String groupName;
	private final byte[] salt;
	private final String creatorName;
	private final byte[] creatorPubKey;
	private final long inviteTimestamp;
	private final byte[] recordSig;

	public GroupTrInviteOfferReceivedEvent(ContactId contactId,
			MessageId messageId, byte[] grouptrGroupId, String groupName,
			byte[] salt, String creatorName, byte[] creatorPubKey,
			long inviteTimestamp, byte[] recordSig) {
		this.contactId = contactId;
		this.messageId = messageId;
		this.grouptrGroupId = grouptrGroupId;
		this.groupName = groupName;
		this.salt = salt;
		this.creatorName = creatorName;
		this.creatorPubKey = creatorPubKey;
		this.inviteTimestamp = inviteTimestamp;
		this.recordSig = recordSig;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public MessageId getMessageId() {
		return messageId;
	}

	public byte[] getGrouptrGroupId() {
		return grouptrGroupId;
	}

	public String getGroupName() {
		return groupName;
	}

	public byte[] getSalt() {
		return salt;
	}

	public String getCreatorName() {
		return creatorName;
	}

	public byte[] getCreatorPubKey() {
		return creatorPubKey;
	}

	public long getInviteTimestamp() {
		return inviteTimestamp;
	}

	public byte[] getRecordSig() {
		return recordSig;
	}
}

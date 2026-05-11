package org.briarproject.briar.api.messaging.event;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupPostReceivedEvent extends Event {

	private final ContactId contactId;
	private final MessageId messageId;
	private final byte[] groupId;
	private final long epoch;
	private final byte[] senderPubKey;
	private final byte[] ciphertext;
	private final long timestamp;
	private final long autoDeleteTimerMs;

	public GroupPostReceivedEvent(ContactId contactId, MessageId messageId,
			byte[] groupId, long epoch, byte[] senderPubKey,
			byte[] ciphertext, long timestamp, long autoDeleteTimerMs) {
		this.contactId = contactId;
		this.messageId = messageId;
		this.groupId = groupId;
		this.epoch = epoch;
		this.senderPubKey = senderPubKey;
		this.ciphertext = ciphertext;
		this.timestamp = timestamp;
		this.autoDeleteTimerMs = autoDeleteTimerMs;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public MessageId getMessageId() {
		return messageId;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public long getEpoch() {
		return epoch;
	}

	public byte[] getSenderPubKey() {
		return senderPubKey;
	}

	public byte[] getCiphertext() {
		return ciphertext;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public long getAutoDeleteTimerMs() {
		return autoDeleteTimerMs;
	}
}

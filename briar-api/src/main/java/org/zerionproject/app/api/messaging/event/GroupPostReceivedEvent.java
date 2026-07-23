package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.MessageId;
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
	private final String senderName;
	private final byte[] ciphertext;
	private final long timestamp;
	private final long autoDeleteTimerMs;
	private final byte[] recordSig;

	public GroupPostReceivedEvent(ContactId contactId, MessageId messageId,
			byte[] groupId, long epoch, byte[] senderPubKey,
			String senderName, byte[] ciphertext, long timestamp,
			long autoDeleteTimerMs) {
		this(contactId, messageId, groupId, epoch, senderPubKey,
				senderName, ciphertext, timestamp, autoDeleteTimerMs,
				new byte[0]);
	}

	public GroupPostReceivedEvent(ContactId contactId, MessageId messageId,
			byte[] groupId, long epoch, byte[] senderPubKey,
			String senderName, byte[] ciphertext, long timestamp,
			long autoDeleteTimerMs, byte[] recordSig) {
		this.contactId = contactId;
		this.messageId = messageId;
		this.groupId = groupId;
		this.epoch = epoch;
		this.senderPubKey = senderPubKey;
		this.senderName = senderName;
		this.ciphertext = ciphertext;
		this.timestamp = timestamp;
		this.autoDeleteTimerMs = autoDeleteTimerMs;
		this.recordSig = recordSig;
	}

	public byte[] getRecordSig() {
		return recordSig;
	}

	public String getSenderName() {
		return senderName;
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

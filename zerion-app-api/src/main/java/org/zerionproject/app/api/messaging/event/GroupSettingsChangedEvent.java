package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupSettingsChangedEvent extends Event {

	private final ContactId contactId;
	private final byte[] groupId;
	private final long autoDeleteTimerMs;
	private final long settingsTimestamp;
	private final byte[] recordSig;
	private final byte[] signedInput;

	public GroupSettingsChangedEvent(ContactId contactId, byte[] groupId,
			long autoDeleteTimerMs, long settingsTimestamp, byte[] recordSig,
			byte[] signedInput) {
		this.contactId = contactId;
		this.groupId = groupId;
		this.autoDeleteTimerMs = autoDeleteTimerMs;
		this.settingsTimestamp = settingsTimestamp;
		this.recordSig = recordSig;
		this.signedInput = signedInput;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public long getAutoDeleteTimerMs() {
		return autoDeleteTimerMs;
	}

	public long getSettingsTimestamp() {
		return settingsTimestamp;
	}

	public byte[] getRecordSig() {
		return recordSig;
	}

	public byte[] getSignedInput() {
		return signedInput;
	}
}

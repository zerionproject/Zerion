package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrInviteResponseReceivedEvent extends Event {

	public enum Kind {ACCEPT, DECLINE}

	private final ContactId contactId;
	private final byte[] grouptrGroupId;
	private final long inviteTimestamp;
	private final byte[] recordSig;
	private final Kind kind;
	@Nullable
	private final String joinerName;

	public GroupTrInviteResponseReceivedEvent(ContactId contactId,
			byte[] grouptrGroupId, long inviteTimestamp, byte[] recordSig,
			Kind kind) {
		this(contactId, grouptrGroupId, inviteTimestamp, recordSig, kind,
				null);
	}

	public GroupTrInviteResponseReceivedEvent(ContactId contactId,
			byte[] grouptrGroupId, long inviteTimestamp, byte[] recordSig,
			Kind kind, @Nullable String joinerName) {
		this.contactId = contactId;
		this.grouptrGroupId = grouptrGroupId;
		this.inviteTimestamp = inviteTimestamp;
		this.recordSig = recordSig;
		this.kind = kind;
		this.joinerName = joinerName;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public byte[] getGrouptrGroupId() {
		return grouptrGroupId;
	}

	public long getInviteTimestamp() {
		return inviteTimestamp;
	}

	public byte[] getRecordSig() {
		return recordSig;
	}

	public Kind getKind() {
		return kind;
	}

	@Nullable
	public String getJoinerName() {
		return joinerName;
	}
}

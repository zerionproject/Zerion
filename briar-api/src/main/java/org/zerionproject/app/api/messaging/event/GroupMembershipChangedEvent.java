package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupMembershipChangedEvent extends Event {

	public enum ChangeKind {
		MEMBER_ADDED,
		MEMBER_REMOVED,
		MEMBER_LEFT,
		GROUP_DISSOLVED,
		ROLE_CHANGED
	}

	private final ContactId contactId;
	private final ChangeKind kind;
	private final byte[] groupId;
	private final long epoch;
	private final long timestamp;
	@Nullable
	private final byte[] targetPubKey;
	@Nullable
	private final String targetName;
	private final long fromEpoch;
	private final long toEpoch;
	private final byte[] recordSig;
	private final byte[] signedInput;
	private final int newRole;

	public GroupMembershipChangedEvent(ContactId contactId, ChangeKind kind,
			byte[] groupId, long epoch, long timestamp,
			@Nullable byte[] targetPubKey, @Nullable String targetName,
			long fromEpoch, long toEpoch,
			byte[] recordSig, byte[] signedInput) {
		this(contactId, kind, groupId, epoch, timestamp, targetPubKey,
				targetName, fromEpoch, toEpoch, recordSig, signedInput,
				0);
	}

	public GroupMembershipChangedEvent(ContactId contactId, ChangeKind kind,
			byte[] groupId, long epoch, long timestamp,
			@Nullable byte[] targetPubKey, @Nullable String targetName,
			long fromEpoch, long toEpoch,
			byte[] recordSig, byte[] signedInput, int newRole) {
		this.contactId = contactId;
		this.kind = kind;
		this.groupId = groupId;
		this.epoch = epoch;
		this.timestamp = timestamp;
		this.targetPubKey = targetPubKey;
		this.targetName = targetName;
		this.fromEpoch = fromEpoch;
		this.toEpoch = toEpoch;
		this.recordSig = recordSig;
		this.signedInput = signedInput;
		this.newRole = newRole;
	}

	public int getNewRole() {
		return newRole;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public ChangeKind getKind() {
		return kind;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public long getEpoch() {
		return epoch;
	}

	public long getTimestamp() {
		return timestamp;
	}

	@Nullable
	public byte[] getTargetPubKey() {
		return targetPubKey;
	}

	@Nullable
	public String getTargetName() {
		return targetName;
	}

	public long getFromEpoch() {
		return fromEpoch;
	}

	public long getToEpoch() {
		return toEpoch;
	}

	public byte[] getRecordSig() {
		return recordSig;
	}

	public byte[] getSignedInput() {
		return signedInput;
	}
}

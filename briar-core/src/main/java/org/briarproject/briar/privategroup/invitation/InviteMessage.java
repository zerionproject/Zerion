package org.briarproject.briar.privategroup.invitation;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
class InviteMessage extends DeletableGroupInvitationMessage {

	private final String groupName;
	private final Author creator;
	private final byte[] salt, signature;
	@Nullable
	private final String text;
	@Nullable
	private final byte[] creatorEphemeralPublic;

	InviteMessage(MessageId id, GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, String groupName, Author creator, byte[] salt,
			@Nullable String text, byte[] signature, long autoDeleteTimer) {
		this(id, contactGroupId, privateGroupId, timestamp, groupName, creator,
				salt, text, signature, autoDeleteTimer, null);
	}

	InviteMessage(MessageId id, GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, String groupName, Author creator, byte[] salt,
			@Nullable String text, byte[] signature, long autoDeleteTimer,
			@Nullable byte[] creatorEphemeralPublic) {
		super(id, contactGroupId, privateGroupId, timestamp, autoDeleteTimer);
		this.groupName = groupName;
		this.creator = creator;
		this.salt = salt;
		this.text = text;
		this.signature = signature;
		this.creatorEphemeralPublic = creatorEphemeralPublic;
	}

	String getGroupName() {
		return groupName;
	}

	Author getCreator() {
		return creator;
	}

	byte[] getSalt() {
		return salt;
	}

	@Nullable
	String getText() {
		return text;
	}

	byte[] getSignature() {
		return signature;
	}

	@Nullable
	byte[] getCreatorEphemeralPublic() {
		return creatorEphemeralPublic;
	}

	boolean isMode3Enabled() {
		return creatorEphemeralPublic != null;
	}
}

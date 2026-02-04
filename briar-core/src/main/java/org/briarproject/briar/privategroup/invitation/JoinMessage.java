package org.briarproject.briar.privategroup.invitation;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
class JoinMessage extends DeletableGroupInvitationMessage {

	@Nullable
	private final MessageId previousMessageId;
	@Nullable
	private final byte[] memberEphemeralPublic;

	JoinMessage(MessageId id, GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, @Nullable MessageId previousMessageId,
			long autoDeleteTimer) {
		this(id, contactGroupId, privateGroupId, timestamp, previousMessageId,
				autoDeleteTimer, null);
	}

	JoinMessage(MessageId id, GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, @Nullable MessageId previousMessageId,
			long autoDeleteTimer, @Nullable byte[] memberEphemeralPublic) {
		super(id, contactGroupId, privateGroupId, timestamp, autoDeleteTimer);
		this.previousMessageId = previousMessageId;
		this.memberEphemeralPublic = memberEphemeralPublic;
	}

	@Nullable
	MessageId getPreviousMessageId() {
		return previousMessageId;
	}

	@Nullable
	byte[] getMemberEphemeralPublic() {
		return memberEphemeralPublic;
	}

	boolean isMode3Response() {
		return memberEphemeralPublic != null;
	}
}

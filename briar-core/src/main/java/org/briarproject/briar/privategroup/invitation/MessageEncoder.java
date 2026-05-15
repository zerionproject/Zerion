package org.briarproject.briar.privategroup.invitation;

import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
interface MessageEncoder {

	BdfDictionary encodeMetadata(MessageType type, GroupId privateGroupId,
			long timestamp, boolean local, boolean read, boolean visible,
			boolean available, boolean accepted, long autoDeleteTimer,
			boolean isAutoDecline);

	BdfDictionary encodeMetadata(MessageType type, GroupId privateGroupId,
			long timestamp, long autoDeleteTimer);

	void setVisibleInUi(BdfDictionary meta, boolean visible);

	void setAvailableToAnswer(BdfDictionary meta, boolean available);

	void setInvitationAccepted(BdfDictionary meta, boolean accepted);

	Message encodeInviteMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, String groupName, Author creator, byte[] salt,
			@Nullable String text, byte[] signature);

	Message encodeInviteMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, String groupName, Author creator, byte[] salt,
			@Nullable String text, byte[] signature, long autoDeleteTimer);

	Message encodeMode3InviteMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, String groupName, Author creator, byte[] salt,
			@Nullable String text, byte[] signature, long autoDeleteTimer,
			byte[] creatorEphemeralPublic);

	Message encodeJoinMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, @Nullable MessageId previousMessageId);

	Message encodeJoinMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, @Nullable MessageId previousMessageId,
			long autoDeleteTimer);

	Message encodeMode3JoinMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, @Nullable MessageId previousMessageId,
			long autoDeleteTimer, byte[] memberEphemeralPublic);

	Message encodeLeaveMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, @Nullable MessageId previousMessageId);

	Message encodeLeaveMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, @Nullable MessageId previousMessageId,
			long autoDeleteTimer);

	Message encodeAbortMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp);
}

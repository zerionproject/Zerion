package org.zerionproject.app.api.privategroup;

import org.zerionproject.core.api.crypto.CryptoExecutor;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import static org.zerionproject.app.api.privategroup.PrivateGroupManager.CLIENT_ID;

@NotNullByDefault
public interface GroupMessageFactory {

	String SIGNING_LABEL_JOIN = CLIENT_ID.getString() + "/JOIN";
	String SIGNING_LABEL_POST = CLIENT_ID.getString() + "/POST";

	@CryptoExecutor
	GroupMessage createJoinMessage(GroupId groupId, long timestamp,
			LocalAuthor creator);

	@CryptoExecutor
	GroupMessage createJoinMessage(GroupId groupId, long timestamp,
			LocalAuthor member, long inviteTimestamp, byte[] creatorSignature);

	@CryptoExecutor
	GroupMessage createGroupMessage(GroupId groupId, long timestamp,
			@Nullable MessageId parentId, LocalAuthor author, String text,
			MessageId previousMsgId);

}

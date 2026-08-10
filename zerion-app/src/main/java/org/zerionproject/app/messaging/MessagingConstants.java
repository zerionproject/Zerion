package org.zerionproject.app.messaging;

import static java.util.concurrent.TimeUnit.DAYS;

interface MessagingConstants {
	String MSG_KEY_TIMESTAMP = "timestamp";
	String MSG_KEY_LOCAL = "local";
	String MSG_KEY_MSG_TYPE = "messageType";
	String MSG_KEY_HAS_TEXT = "hasText";
	String MSG_KEY_ATTACHMENT_HEADERS = "attachmentHeaders";
	String MSG_KEY_AUTO_DELETE_TIMER = "autoDeleteTimer";
	String MSG_KEY_CHUNK_INDEX = "chunkIndex";
	String MSG_KEY_CHUNK_COUNT = "chunkCount";
	String MSG_KEY_MANIFEST_ID = "manifestId";
	String MSG_KEY_TOTAL_SIZE = "totalSize";
	String MSG_KEY_ROOT_HASH = "rootHash";

	String MSG_KEY_TARGET_MESSAGE_ID = "targetMessageId";
	String MSG_KEY_REACTION_EMOJI = "reactionEmoji";
	String MSG_KEY_IS_TYPING = "isTyping";

	String MSG_KEY_REPLY_TO_ID = "replyToId";

	String MSG_KEY_MESH = "mesh";

	String MSG_KEY_MESH_STATE = "meshState";

	String MSG_KEY_MESH_SENDER_ID = "meshSenderId";

	String MSG_KEY_PREVIEW_URL = "previewUrl";
	String MSG_KEY_PREVIEW_TITLE = "previewTitle";
	String MSG_KEY_PREVIEW_DESCRIPTION = "previewDesc";
	String MSG_KEY_HAS_PREVIEW_IMAGE = "hasPreviewImage";

	String MSG_KEY_GROUP_ID = "groupId";
	String MSG_KEY_GROUP_EPOCH = "groupEpoch";
	String MSG_KEY_GROUP_SENDER_PUBKEY = "groupSenderPubKey";
	String MSG_KEY_GROUP_CIPHERTEXT = "groupCiphertext";
	String MSG_KEY_GROUP_RECORD_SIG = "groupRecordSig";
	String MSG_KEY_GROUP_ADDED_PUBKEY = "groupAddedPubKey";
	String MSG_KEY_GROUP_ADDED_NAME = "groupAddedName";
	String MSG_KEY_GROUP_REMOVED_PUBKEY = "groupRemovedPubKey";
	String MSG_KEY_GROUP_LEAVING_PUBKEY = "groupLeavingPubKey";
	String MSG_KEY_GROUP_FROM_EPOCH = "groupFromEpoch";
	String MSG_KEY_GROUP_TO_EPOCH = "groupToEpoch";
	String MSG_KEY_GROUP_PQ_SEED = "groupPqSeed";
	String MSG_KEY_GROUP_TARGET_PUBKEY = "groupTargetPubKey";
	String MSG_KEY_GROUP_NEW_ROLE = "groupNewRole";
	String MSG_KEY_GROUP_MEMBER_LIST = "groupMemberList";

	String MSG_KEY_GTR_INVITE_NAME = "gtrInviteName";
	String MSG_KEY_GTR_INVITE_SALT = "gtrInviteSalt";
	String MSG_KEY_GTR_INVITE_CREATOR_NAME = "gtrInviteCreatorName";
	String MSG_KEY_GTR_INVITE_CREATOR_PUB = "gtrInviteCreatorPub";

	String SIGNING_LABEL_GROUP_MEMBER_LIST_SNAPSHOT =
			"org.zerionproject/GROUP_MEMBER_LIST_SNAPSHOT";

	String SIGNING_LABEL_GROUP_POST =
			"org.zerionproject/GROUP_POST";
	String SIGNING_LABEL_GROUP_MEMBERSHIP =
			"org.zerionproject/GROUP_MEMBERSHIP";
	String SIGNING_LABEL_GROUP_EPOCH_COMMIT =
			"org.zerionproject/GROUP_EPOCH_COMMIT";

	long MISSING_ATTACHMENT_CLEANUP_DURATION_MS = DAYS.toMillis(28);
}

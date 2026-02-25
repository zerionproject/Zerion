package org.briarproject.briar.messaging;

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

	String MSG_KEY_PREVIEW_URL = "previewUrl";
	String MSG_KEY_PREVIEW_TITLE = "previewTitle";
	String MSG_KEY_PREVIEW_DESCRIPTION = "previewDesc";
	String MSG_KEY_HAS_PREVIEW_IMAGE = "hasPreviewImage";

	long MISSING_ATTACHMENT_CLEANUP_DURATION_MS = DAYS.toMillis(28);
}

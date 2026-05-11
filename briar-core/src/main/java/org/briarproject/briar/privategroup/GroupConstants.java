package org.briarproject.briar.privategroup;

import static org.briarproject.briar.client.MessageTrackerConstants.MSG_KEY_READ;

interface GroupConstants {
	String KEY_TYPE = "type";
	String KEY_TIMESTAMP = "timestamp";
	String KEY_READ = MSG_KEY_READ;
	String KEY_PARENT_MSG_ID = "parentMsgId";
	String KEY_PREVIOUS_MSG_ID = "previousMsgId";
	String KEY_MEMBER = "member";
	String KEY_INITIAL_JOIN_MSG = "initialJoinMsg";

	String GROUP_KEY_MEMBERS = "members";
	String GROUP_KEY_OUR_GROUP = "ourGroup";
	String GROUP_KEY_CREATOR_ID = "creatorId";
	String GROUP_KEY_DISSOLVED = "dissolved";
	String GROUP_KEY_VISIBILITY = "visibility";

	// Sender Keys message metadata
	String KEY_CIPHERTEXT = "ciphertext";
	String KEY_NONCE = "nonce";
	String KEY_EPOCH = "epoch";
	String KEY_MESSAGE_INDEX = "messageIndex";
	String KEY_SIGNATURE = "signature";

	// Per-message disappearing-messages TTL (group-v2). Persisted on
	// SENDER_KEYS_POST only when the sender opted in for that send —
	// absence on a stored row means "permanent". Stored as Long ms.
	String KEY_AUTO_DELETE_TIMER = "autoDeleteTimer";

	// Group-membership v2 record metadata (msgType 10–14). The author-id
	// fields here are the raw 32-byte Ed25519 signing public key (iOS
	// convention), not Briar's SHA-256-derived authorId. The recipient
	// manager is responsible for resolving this pubkey to a local Briar
	// authorId when applying state changes.
	String KEY_ADDED_AUTHOR_ID = "addedAuthorId";
	String KEY_ADDED_AUTHOR_NAME = "addedAuthorName";
	String KEY_REMOVED_AUTHOR_ID = "removedAuthorId";
	String KEY_LEAVING_AUTHOR_ID = "leavingAuthorId";
	String KEY_FROM_AUTHOR_ID = "fromAuthorId";
	String KEY_CHAIN_KEY = "chainKey";
	String KEY_PUBLIC_SIGNING_KEY = "publicSigningKey";

}

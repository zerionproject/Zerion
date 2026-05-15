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

	String KEY_CIPHERTEXT = "ciphertext";
	String KEY_NONCE = "nonce";
	String KEY_EPOCH = "epoch";
	String KEY_MESSAGE_INDEX = "messageIndex";
	String KEY_SIGNATURE = "signature";

}

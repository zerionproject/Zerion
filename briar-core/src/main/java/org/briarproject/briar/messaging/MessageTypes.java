package org.briarproject.briar.messaging;

public interface MessageTypes {

	int PRIVATE_MESSAGE = 0;
	int ATTACHMENT = 1;
	int VOICE_SIGNAL = 2;
	int ATTACHMENT_MANIFEST = 3;
	int ATTACHMENT_CHUNK = 4;
	// 5 was SENDER_KEY_DISTRIBUTION — removed in 391027f (Sender Keys
	//   replaced by GroupTr Triple Ratchet). Reserved, do not reuse.
	// 6 was REKEY_REQUEST — same provenance, same reservation.
	int MESSAGE_REACTION = 7;
	int TYPING_INDICATOR = 8;
	int LINK_PREVIEW_MESSAGE = 9;

	int GROUP_POST = 32;
	int GROUP_MEMBER_ADDED = 33;
	int GROUP_MEMBER_REMOVED = 34;
	int GROUP_MEMBER_LEFT = 35;
	int GROUP_DISSOLVED = 36;
	int GROUP_EPOCH_COMMIT = 37;
	int GROUP_MEMBER_ROLE_CHANGED = 38;
	int GROUP_MEMBER_KEY_ROTATED_RESERVED = 39;
	int GROUP_FORWARDED_RESERVED = 40;
	int GROUP_MEMBER_LIST_SNAPSHOT = 41;
}

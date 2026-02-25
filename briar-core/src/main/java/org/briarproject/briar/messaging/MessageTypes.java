package org.briarproject.briar.messaging;

public interface MessageTypes {

	int PRIVATE_MESSAGE = 0;
	int ATTACHMENT = 1;
	int VOICE_SIGNAL = 2;
	int ATTACHMENT_MANIFEST = 3;
	int ATTACHMENT_CHUNK = 4;
	int SENDER_KEY_DISTRIBUTION = 5;
	int REKEY_REQUEST = 6;
	int MESSAGE_REACTION = 7;
	int TYPING_INDICATOR = 8;
	int LINK_PREVIEW_MESSAGE = 9;
}

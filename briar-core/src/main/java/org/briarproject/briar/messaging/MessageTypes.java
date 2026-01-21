package org.briarproject.briar.messaging;

interface MessageTypes {

	int PRIVATE_MESSAGE = 0;
	int ATTACHMENT = 1;
	int VOICE_SIGNAL = 2;

	// Chunked attachment types for large media (video/audio)
	int ATTACHMENT_MANIFEST = 3;
	int ATTACHMENT_CHUNK = 4;
}

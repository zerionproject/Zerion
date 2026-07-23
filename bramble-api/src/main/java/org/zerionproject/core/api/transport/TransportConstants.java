package org.zerionproject.core.api.transport;

import org.zerionproject.core.api.crypto.SecretKey;

public interface TransportConstants {

	int PROTOCOL_VERSION = 4;

	int TAG_LENGTH = 16;

	int STREAM_HEADER_NONCE_LENGTH = 24;

	int MAC_LENGTH = 16;

	int STREAM_HEADER_PLAINTEXT_LENGTH = 2 + 8 + SecretKey.LENGTH;

	int STREAM_HEADER_LENGTH = STREAM_HEADER_NONCE_LENGTH
			+ STREAM_HEADER_PLAINTEXT_LENGTH + MAC_LENGTH;

	int FRAME_NONCE_LENGTH = 24;

	int FRAME_HEADER_PLAINTEXT_LENGTH = 4;

	int FRAME_HEADER_LENGTH = FRAME_HEADER_PLAINTEXT_LENGTH + MAC_LENGTH;

	int MAX_FRAME_LENGTH = 4096;

	int MAX_PAYLOAD_LENGTH = MAX_FRAME_LENGTH - FRAME_HEADER_LENGTH
			- MAC_LENGTH;

	int MAX_CLOCK_DIFFERENCE = 24 * 60 * 60 * 1000;

	int REORDERING_WINDOW_SIZE = 32;

	String STATIC_MASTER_KEY_LABEL =
			"org.zerionproject.core.transport/STATIC_MASTER_KEY";

	String PENDING_CONTACT_ROOT_KEY_LABEL =
			"org.zerionproject.core.transport/PENDING_CONTACT_ROOT_KEY";

	String CONTACT_ROOT_KEY_LABEL =
			"org.zerionproject.core.transport/CONTACT_ROOT_KEY";

	String ALICE_TAG_LABEL = "org.zerionproject.core.transport/ALICE_TAG_KEY";

	String BOB_TAG_LABEL = "org.zerionproject.core.transport/BOB_TAG_KEY";

	String ALICE_HEADER_LABEL =
			"org.zerionproject.core.transport/ALICE_HEADER_KEY";

	String BOB_HEADER_LABEL =
			"org.zerionproject.core.transport/BOB_HEADER_KEY";

	String ROTATE_LABEL = "org.zerionproject.core.transport/ROTATE";

	String ALICE_HANDSHAKE_TAG_LABEL =
			"org.zerionproject.core.transport/ALICE_HANDSHAKE_TAG_KEY";

	String BOB_HANDSHAKE_TAG_LABEL =
			"org.zerionproject.core.transport/BOB_HANDSHAKE_TAG_KEY";

	String ALICE_HANDSHAKE_HEADER_LABEL =
			"org.zerionproject.core.transport/ALICE_HANDSHAKE_HEADER_KEY";

	String BOB_HANDSHAKE_HEADER_LABEL =
			"org.zerionproject.core.transport/BOB_HANDSHAKE_HEADER_KEY";
}

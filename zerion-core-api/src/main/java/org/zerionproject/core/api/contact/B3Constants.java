package org.zerionproject.core.api.contact;

public interface B3Constants {

	boolean B3_PROOF_ENABLED = true;

	String B3_KEY_PROOF_LABEL = "ZERION_PQ_KEY_PROOF_v1";

	String B3_HANDSHAKE_SESSION_LABEL = "ZERION_HANDSHAKE_SESSION_v1";

	byte B3_ROLE_ALICE = 0x01;

	byte B3_ROLE_BOB = 0x02;

	int B3_SESSION_ID_LEN = 32;

	int B3_PQ_PUB_LEN = 1184;

	int B3_SIG_LEN = 64;

	int B3_SIG_INPUT_LEN = 1251;

	String B3_SETTINGS_NAMESPACE = "b3";
	String B3_SLOT_PRESENT_KEY_PREFIX = "slot_present.";
	String B3_PEER_MESSAGING_MINOR_KEY_PREFIX = "peer_messaging_minor.";
	String B3_STRICT_REJECT_KEY_PREFIX = "strict_reject.";
}

package org.briarproject.bramble.contact;

import static org.briarproject.bramble.api.crypto.CryptoConstants.MAC_BYTES;

interface HandshakeConstants {

	
	byte PROTOCOL_MAJOR_VERSION = 0;

	
	byte PROTOCOL_MINOR_VERSION = 1;

	
	@Deprecated
	String MASTER_KEY_LABEL_0_0 =
			"org.briarproject.bramble.handshake/MASTER_KEY";

	
	String MASTER_KEY_LABEL_0_1 =
			"org.briarproject.bramble.handshake/MASTER_KEY_0_1";

	
	String MASTER_KEY_LABEL_HYBRID =
			"org.briarproject.bramble.handshake/HYBRID_MASTER_KEY_V1";

	
	String ALICE_PROOF_LABEL = "org.briarproject.bramble.handshake/ALICE_PROOF";

	
	String BOB_PROOF_LABEL = "org.briarproject.bramble.handshake/BOB_PROOF";

	
	int PROOF_BYTES = MAC_BYTES;
}

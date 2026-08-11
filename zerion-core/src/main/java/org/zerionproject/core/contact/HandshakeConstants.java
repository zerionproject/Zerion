package org.zerionproject.core.contact;

import static org.zerionproject.core.api.crypto.CryptoConstants.MAC_BYTES;

interface HandshakeConstants {

	byte PROTOCOL_MAJOR_VERSION = 0;

	byte PROTOCOL_MINOR_VERSION = 2;

	byte FS_MINOR_VERSION = 2;

	String MASTER_KEY_LABEL_HYBRID =
			"org.zerionproject.core.handshake/HYBRID_MASTER_KEY_V1";

	String MASTER_KEY_LABEL_HYBRID_FS =
			"org.zerionproject.core.handshake/HYBRID_MASTER_KEY_FS_V2";

	String ALICE_PROOF_LABEL = "org.zerionproject.core.handshake/ALICE_PROOF";

	String BOB_PROOF_LABEL = "org.zerionproject.core.handshake/BOB_PROOF";

	int PROOF_BYTES = MAC_BYTES;
}

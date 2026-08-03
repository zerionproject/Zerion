package org.zerionproject.core.api.contact;

import java.util.regex.Pattern;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;

public interface HandshakeLinkConstants {

	int FORMAT_VERSION_CLASSICAL = 0;

	int BASE32_LINK_BYTES_CLASSICAL = 53;

	int RAW_LINK_BYTES_CLASSICAL = 33;

	int FORMAT_VERSION_HYBRID = 1;

	int HYBRID_COMMITMENT_BYTES = 32;

	int HYBRID_RENDEZVOUS_X25519_BYTES = 32;

	int BASE32_LINK_BYTES_HYBRID = 104;

	int RAW_LINK_BYTES_HYBRID =
			1 + HYBRID_COMMITMENT_BYTES + HYBRID_RENDEZVOUS_X25519_BYTES;

	int HYBRID_PUBLIC_KEY_BYTES = HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;

	int FORMAT_VERSION = FORMAT_VERSION_HYBRID;

	int BASE32_LINK_BYTES = BASE32_LINK_BYTES_HYBRID;

	int RAW_LINK_BYTES = RAW_LINK_BYTES_HYBRID;

	Pattern LINK_REGEX =
			Pattern.compile("^zerion://([a-z2-7]{" + BASE32_LINK_BYTES_CLASSICAL
					+ "}|[a-z2-7]{" + BASE32_LINK_BYTES_HYBRID
					+ "})(?:\\?.*)?$");

	String ID_LABEL = "org.zerionproject.core/HANDSHAKE_KEY_ID";

	String HYBRID_COMMITMENT_LABEL =
			"org.zerionproject.core/HYBRID_KEY_COMMITMENT";
}

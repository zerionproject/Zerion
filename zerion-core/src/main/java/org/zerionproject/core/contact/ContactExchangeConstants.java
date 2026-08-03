package org.zerionproject.core.contact;

interface ContactExchangeConstants {

	byte PROTOCOL_VERSION = 1;

	String ALICE_KEY_LABEL =
			"org.zerionproject.core.contact/ALICE_HEADER_KEY";

	String BOB_KEY_LABEL = "org.zerionproject.core.contact/BOB_HEADER_KEY";

	String ALICE_NONCE_LABEL = "org.zerionproject.core.contact/ALICE_NONCE";

	String BOB_NONCE_LABEL = "org.zerionproject.core.contact/BOB_NONCE";

	String SIGNING_LABEL = "org.zerionproject.app.contact/EXCHANGE";
}

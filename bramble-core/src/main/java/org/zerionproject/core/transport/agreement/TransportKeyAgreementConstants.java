package org.zerionproject.core.transport.agreement;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
interface TransportKeyAgreementConstants {

	String MSG_KEY_IS_SESSION = "isSession";
	String MSG_KEY_MESSAGE_TYPE = "messageType";
	String MSG_KEY_TRANSPORT_ID = "transportId";
	String MSG_KEY_PUBLIC_KEY = "publicKey";
	String MSG_KEY_LOCAL = "local";

	String SESSION_KEY_STATE = "state";
	String SESSION_KEY_LAST_LOCAL_MESSAGE_ID = "lastLocalMessageId";
	String SESSION_KEY_LOCAL_PUBLIC_KEY = "localPublicKey";
	String SESSION_KEY_LOCAL_PRIVATE_KEY = "localPrivateKey";
	String SESSION_KEY_LOCAL_TIMESTAMP = "localTimestamp";
	String SESSION_KEY_KEY_SET_ID = "keySetId";

	String ROOT_KEY_LABEL =
			"org.zerionproject.core.transport.agreement/ROOT_KEY";

}

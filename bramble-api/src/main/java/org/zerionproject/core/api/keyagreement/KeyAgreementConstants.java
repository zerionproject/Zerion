package org.zerionproject.core.api.keyagreement;

public interface KeyAgreementConstants {

	byte PROTOCOL_VERSION = 4;

	int COMMIT_LENGTH = 16;

	long CONNECTION_TIMEOUT = 60_000;

	int TRANSPORT_ID_LAN = 1;

	int TRANSPORT_ID_BLUETOOTH = 2;

	String SHARED_SECRET_LABEL =
			"org.zerionproject.core.keyagreement/SHARED_SECRET";

	String MASTER_KEY_LABEL =
			"org.zerionproject.core.keyagreement/MASTER_SECRET";
}

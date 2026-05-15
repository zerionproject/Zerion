package org.briarproject.bramble.api.keyagreement;

public interface KeyAgreementConstants {

	byte PROTOCOL_VERSION = 4;

	int COMMIT_LENGTH = 16;

	long CONNECTION_TIMEOUT = 60_000;

	int TRANSPORT_ID_LAN = 1;

	String SHARED_SECRET_LABEL =
			"org.briarproject.bramble.keyagreement/SHARED_SECRET";

	String MASTER_KEY_LABEL =
			"org.briarproject.bramble.keyagreement/MASTER_SECRET";
}

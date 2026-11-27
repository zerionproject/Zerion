package org.zerionproject.core.api.keyagreement;

public interface KeyAgreementConstants {

	byte PROTOCOL_VERSION = 5;

	int COMMIT_LENGTH = 16;

	long CONNECTION_TIMEOUT = 60_000;

	int TRANSPORT_ID_LAN = 1;

	int TRANSPORT_ID_BLUETOOTH = 2;

	String SHARED_SECRET_LABEL =
			"org.zerionproject.core.keyagreement/SHARED_SECRET";

	String HYBRID_SHARED_SECRET_LABEL =
			"org.zerionproject.core.keyagreement/HYBRID_SHARED_SECRET_V5";

	String MASTER_KEY_LABEL =
			"org.zerionproject.core.keyagreement/MASTER_SECRET";

	byte CAPABILITY_KEY_SEPARATION = 0x01;

	int TRANSPORT_ID_CAPABILITIES = 0x7F;

	String HYBRID_SHARED_SECRET_LABEL_SEPARATED =
			"org.zerionproject.core.keyagreement/HYBRID_SHARED_SECRET_V5_SEPARATED";

	String EXCHANGE_KEY_LABEL =
			"org.zerionproject.core.keyagreement/EXCHANGE_KEY";
}

package org.briarproject.bramble.api.keyagreement;

import org.briarproject.nullsafety.NotNullByDefault;


@NotNullByDefault
public interface KeyAgreementTask {

	
	void listen();

	
	void stopListening();

	
	void connectAndRunProtocol(Payload remotePayload);
}

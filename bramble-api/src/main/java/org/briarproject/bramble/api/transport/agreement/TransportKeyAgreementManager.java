package org.briarproject.bramble.api.transport.agreement;

import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface TransportKeyAgreementManager {

	
	ClientId CLIENT_ID =
			new ClientId("org.briarproject.bramble.transport.agreement");

	
	int MAJOR_VERSION = 0;

	
	int MINOR_VERSION = 0;
}

package org.zerionproject.core.api.transport.agreement;

import org.zerionproject.core.api.sync.ClientId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface TransportKeyAgreementManager {

	ClientId CLIENT_ID =
			new ClientId("org.zerionproject.core.transport.agreement");

	int MAJOR_VERSION = 0;

	int MINOR_VERSION = 0;
}

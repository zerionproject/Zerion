package org.briarproject.bramble.rendezvous;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.SECONDS;

interface RendezvousConstants {

	
	byte PROTOCOL_VERSION = 0;

	
	long RENDEZVOUS_TIMEOUT_MS = DAYS.toMillis(2);

	
	long POLLING_INTERVAL_MS = SECONDS.toMillis(30);

	// Faster polling for the first minute after adding a contact
	long FAST_POLLING_INTERVAL_MS = SECONDS.toMillis(10);
	long FAST_POLLING_DURATION_MS = SECONDS.toMillis(60);

	
	String RENDEZVOUS_KEY_LABEL =
			"org.briarproject.bramble.rendezvous/RENDEZVOUS_KEY";

	
	String KEY_MATERIAL_LABEL =
			"org.briarproject.bramble.rendezvous/KEY_MATERIAL";

	
	String HYBRID_RENDEZVOUS_KEY_LABEL =
			"org.briarproject.bramble.rendezvous/HYBRID_RENDEZVOUS_KEY";
}

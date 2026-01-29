package org.briarproject.bramble.api.cleanup;

import org.briarproject.bramble.api.cleanup.event.CleanupTimerStartedEvent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;


@NotNullByDefault
public interface CleanupManager {

	
	long BATCH_DELAY_MS = 1000;

	
	void registerCleanupHook(ClientId c, int majorVersion,
			CleanupHook hook);
}

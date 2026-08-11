package org.zerionproject.core.api.cleanup;

import org.zerionproject.core.api.cleanup.event.CleanupTimerStartedEvent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface CleanupManager {

	long BATCH_DELAY_MS = 1000;

	void registerCleanupHook(ClientId c, int majorVersion,
			CleanupHook hook);
}

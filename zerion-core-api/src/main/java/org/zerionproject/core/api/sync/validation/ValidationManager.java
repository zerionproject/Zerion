package org.zerionproject.core.api.sync.validation;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.ClientId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ValidationManager {

	void registerMessageValidator(ClientId c, int majorVersion,
			MessageValidator v);

	void registerIncomingMessageHook(ClientId c, int majorVersion,
			IncomingMessageHook hook);
}

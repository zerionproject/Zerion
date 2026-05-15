package org.briarproject.bramble.api.sync.validation;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ValidationManager {

	void registerMessageValidator(ClientId c, int majorVersion,
			MessageValidator v);

	void registerIncomingMessageHook(ClientId c, int majorVersion,
			IncomingMessageHook hook);
}

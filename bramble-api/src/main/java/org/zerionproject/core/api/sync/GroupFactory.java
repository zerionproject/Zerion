package org.zerionproject.core.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface GroupFactory {

	Group createGroup(ClientId c, int majorVersion, byte[] descriptor);
}

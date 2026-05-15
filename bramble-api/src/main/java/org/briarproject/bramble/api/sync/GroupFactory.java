package org.briarproject.bramble.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface GroupFactory {

	Group createGroup(ClientId c, int majorVersion, byte[] descriptor);
}

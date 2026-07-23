package org.zerionproject.core.api.reliability;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

@NotNullByDefault
public interface WriteHandler {

	void handleWrite(byte[] b) throws IOException;
}

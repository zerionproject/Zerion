package org.zerionproject.transport.i2p;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

@NotNullByDefault
public interface I2pRouter {

	void start() throws IOException;

	void stop();
}

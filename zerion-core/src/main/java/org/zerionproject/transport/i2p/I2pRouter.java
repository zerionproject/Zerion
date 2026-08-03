package org.zerionproject.transport.i2p;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

/**
 * Owns the I2P router the transport talks to over SAM. Two implementations are
 * expected: one that assumes an externally-run router (a standalone i2pd app or
 * an i2pd on the host, useful for testing), and a future one that starts a
 * bundled native i2pd. The transport calls {@link #start} before opening its
 * SAM session and {@link #stop} when it shuts down.
 */
@NotNullByDefault
public interface I2pRouter {

	/**
	 * Ensures the router is running and its SAM bridge is reachable, blocking
	 * until it is or throwing if it cannot be reached. A bundled router starts
	 * the process here; an external router only probes the bridge.
	 */
	void start() throws IOException;

	/** Stops the router if this implementation owns it; a no-op otherwise. */
	void stop();
}

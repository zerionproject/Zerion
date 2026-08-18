package org.zerionproject.transport.i2p;

import org.zerionproject.transport.OverlayTransport;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;

/**
 * The I2P transport as the plugin drives it: it dials and accepts like any
 * {@link OverlayTransport}, and additionally starts from a persisted private
 * key (recreating the same destination) and stops. Two implementations exist:
 * one over a SAM bridge to an externally-run router, and one over I2CP to an
 * embedded router bundled in the app.
 */
@NotNullByDefault
public interface I2pOverlayTransport extends OverlayTransport {

	/**
	 * Starts the transport, recreating the destination from {@code privateKey}
	 * when non-null, and returns the destination to publish and the key to
	 * persist.
	 */
	I2pDestination start(@Nullable String privateKey) throws IOException;

	/**
	 * Registers a callback invoked once the transport's session is ready to
	 * carry traffic. If the session is already ready when this is called, the
	 * callback runs immediately. Used to report the plugin as active only once
	 * it can actually connect, rather than as soon as start() returns.
	 */
	void setOnSessionReady(Runnable callback);

	/** Stops the transport and the router it owns. */
	void stop();
}

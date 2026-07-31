package org.zerionproject.transport.i2p;

import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.transport.ZtpConnectionHandler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * The I2P stack over a SAM bridge to an externally-run router. This is the
 * release build's binding; with the plugin disabled by default there it is not
 * exercised, but it keeps the graph buildable and drives the transport when an
 * external router is available.
 */
@NotNullByDefault
public class SamI2pStack implements I2pStack {

	@Inject
	public SamI2pStack() {
	}

	@Override
	public I2pOverlayTransport createTransport(Executor ioExecutor,
			ZtpConnectionHandler handler) {
		I2pRouter router = new ExternalI2pRouter(I2pConstants.DEFAULT_SAM_HOST,
				I2pConstants.DEFAULT_SAM_PORT, I2pConstants.SAM_CONNECT_TIMEOUT);
		return new I2pTransport(I2pConstants.DEFAULT_SAM_HOST,
				I2pConstants.DEFAULT_SAM_PORT, router, ioExecutor, handler);
	}
}

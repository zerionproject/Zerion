package org.zerionproject.transport.i2p;

import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.transport.ZtpConnectionHandler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

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

package org.zerionproject.transport.i2p;

import org.zerionproject.transport.ZtpConnectionHandler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

@NotNullByDefault
public interface I2pStack {

	I2pOverlayTransport createTransport(Executor ioExecutor,
			ZtpConnectionHandler handler);
}

package org.zerionproject.transport.i2p;

import org.zerionproject.transport.ZtpConnectionHandler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

/**
 * Supplies the I2P transport for the build. The release build binds a stack over
 * a SAM bridge to an externally-run router; the debug build binds a stack over
 * an embedded, bundled router. The plugin factory asks the stack for a transport
 * so it does not need to know which router backs it.
 */
@NotNullByDefault
public interface I2pStack {

	I2pOverlayTransport createTransport(Executor ioExecutor,
			ZtpConnectionHandler handler);
}

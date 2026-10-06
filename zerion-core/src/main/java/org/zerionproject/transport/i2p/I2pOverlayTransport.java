package org.zerionproject.transport.i2p;

import org.zerionproject.transport.OverlayTransport;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;

@NotNullByDefault
public interface I2pOverlayTransport extends OverlayTransport {

	I2pDestination start(@Nullable String privateKey) throws IOException;

	void setOnSessionReady(Runnable callback);

	void stop();
}

package org.zerionproject.core.api.plugin;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;

@NotNullByDefault
public interface TransportConnectionWriter {

	long getMaxLatency();

	int getMaxIdleTime();

	boolean isLossyAndCheap();

	OutputStream getOutputStream() throws IOException;

	void dispose(boolean exception) throws IOException;
}

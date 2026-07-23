package org.zerionproject.core.api.plugin;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

@NotNullByDefault
public interface TransportConnectionReader {

	InputStream getInputStream() throws IOException;

	void dispose(boolean exception, boolean recognised) throws IOException;
}

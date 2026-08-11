package org.zerionproject.core.api.io;

import java.io.InputStream;

public interface TimeoutMonitor {

	InputStream createTimeoutInputStream(InputStream in, long timeoutMs);
}

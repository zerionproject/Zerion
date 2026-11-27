package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

@NotNullByDefault
public interface ZtpReadTimeoutControl {

	void setReadTimeout(int timeoutMs) throws IOException;
}

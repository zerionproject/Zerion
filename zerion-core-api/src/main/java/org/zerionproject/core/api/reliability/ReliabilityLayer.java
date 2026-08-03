package org.zerionproject.core.api.reliability;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;
import java.io.OutputStream;

@NotNullByDefault
public interface ReliabilityLayer extends ReadHandler {

	void start();

	void stop();

	InputStream getInputStream();

	OutputStream getOutputStream();
}

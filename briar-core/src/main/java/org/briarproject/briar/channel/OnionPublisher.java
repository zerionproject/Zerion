package org.briarproject.briar.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

@NotNullByDefault
public interface OnionPublisher {

	String publish(int localPort) throws IOException;

	void unpublish(String onion) throws IOException;
}

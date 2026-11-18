package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;

@NotNullByDefault
public interface ZppConnectionRunner {

	void run(int contactId, ZwfDuplexConnection connection) throws IOException;

	default void run(int contactId, ZwfDuplexConnection connection,
			@Nullable ZtpReadTimeoutControl readTimeout) throws IOException {
		run(contactId, connection);
	}
}

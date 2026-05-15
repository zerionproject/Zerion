package org.briarproject.bramble.plugin.modem;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@NotNullByDefault
interface Modem {

	boolean start() throws IOException;

	void stop() throws IOException;

	boolean dial(String number) throws IOException;

	InputStream getInputStream() throws IOException;

	OutputStream getOutputStream() throws IOException;

	void hangUp() throws IOException;

	interface Callback {

		void incomingCallConnected();
	}
}

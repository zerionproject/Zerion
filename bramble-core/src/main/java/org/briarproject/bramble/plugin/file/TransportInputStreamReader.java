package org.briarproject.bramble.plugin.file;

import org.briarproject.bramble.api.plugin.TransportConnectionReader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;
import static org.briarproject.bramble.util.IoUtils.tryToClose;

@NotNullByDefault
class TransportInputStreamReader implements TransportConnectionReader {
	private final InputStream in;

	TransportInputStreamReader(InputStream in) {
		this.in = in;
	}

	@Override
	public InputStream getInputStream() {
		return in;
	}

	@Override
	public void dispose(boolean exception, boolean recognised) {
		tryToClose(in);
	}
}

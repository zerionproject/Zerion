package org.briarproject.bramble.plugin.tor;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

@NotNullByDefault
public interface ChannelOnionAdapter {

	String publishChannelOnion(int localPort) throws IOException;

	void removeChannelOnion(String onion) throws IOException;
}

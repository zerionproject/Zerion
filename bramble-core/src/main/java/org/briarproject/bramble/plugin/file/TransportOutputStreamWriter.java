package org.briarproject.bramble.plugin.file;

import org.briarproject.bramble.api.plugin.TransportConnectionWriter;
import org.briarproject.bramble.api.plugin.simplex.SimplexPlugin;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.OutputStream;
import static org.briarproject.bramble.util.IoUtils.tryToClose;

@NotNullByDefault
class TransportOutputStreamWriter implements TransportConnectionWriter {
	private final SimplexPlugin plugin;
	private final OutputStream out;

	TransportOutputStreamWriter(SimplexPlugin plugin, OutputStream out) {
		this.plugin = plugin;
		this.out = out;
	}

	@Override
	public long getMaxLatency() {
		return plugin.getMaxLatency();
	}

	@Override
	public int getMaxIdleTime() {
		return plugin.getMaxIdleTime();
	}

	@Override
	public boolean isLossyAndCheap() {
		return plugin.isLossyAndCheap();
	}

	@Override
	public OutputStream getOutputStream() {
		return out;
	}

	@Override
	public void dispose(boolean exception) {
		tryToClose(out);
	}
}

package org.briarproject.onionwrapper;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;

import static java.util.logging.Level.INFO;

@NotNullByDefault
public class MacTorWrapper extends UnixTorWrapper {

	static final String LIB_EVENT_VERSION = "2.1.7";

	public MacTorWrapper(Executor ioExecutor,
			Executor eventExecutor,
			String architecture,
			File torDirectory,
			int torSocksPort,
			int torControlPort) {
		super(ioExecutor, eventExecutor, architecture, torDirectory, torSocksPort, torControlPort);
	}

	@Override
	protected void installTorExecutable() throws IOException {
		super.installTorExecutable();
		installLibEvent();
	}

	private void installLibEvent() throws IOException {
		if (LOG.isLoggable(INFO)) {
			LOG.info("Installing libevent binary for " + architecture);
		}
		File libEventFile = getLibEventFile();
		extract(getExecutableInputStream("libevent-" + LIB_EVENT_VERSION + ".dylib"),
				libEventFile);
	}

	private File getLibEventFile() {
		return new File(torDirectory, "libevent-" + LIB_EVENT_VERSION + ".dylib");
	}

	@Override
	protected void extract(InputStream in, File dest) throws IOException {

		dest.delete();
		super.extract(in, dest);
	}
}

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

	/**
	 * @param ioExecutor The wrapper will use this executor to run IO tasks,
	 * 		some of which may run for the lifetime of the wrapper, so the executor
	 * 		should have an unlimited thread pool.
	 * @param eventExecutor The wrapper will use this executor to call the
	 *        {@link Observer observer} (if any). To ensure that events are observed
	 * 		in the order they occur, this executor should have a single thread (eg
	 * 		the app's main thread).
	 * @param architecture The processor architecture of the Tor and pluggable
	 * 		transport binaries.
	 * @param torDirectory The directory where the Tor process should keep its
	 * 		state.
	 * @param torSocksPort The port number to use for Tor's SOCKS port.
	 * @param torControlPort The port number to use for Tor's control port.
	 */
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
		// Important: delete file to prevent problems on macOS in case the file signature changed
		// for binaries.
		//noinspection ResultOfMethodCallIgnored
		dest.delete();
		super.extract(in, dest);
	}
}

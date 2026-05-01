package org.briarproject.onionwrapper;

import com.sun.jna.platform.win32.Kernel32;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.util.concurrent.Executor;

/**
 * A Tor wrapper for the Windows operating system.
 */
@NotNullByDefault
public class WindowsTorWrapper extends JavaTorWrapper {

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
	public WindowsTorWrapper(Executor ioExecutor,
			Executor eventExecutor,
			String architecture,
			File torDirectory,
			int torSocksPort,
			int torControlPort) {
		super(ioExecutor, eventExecutor, architecture, torDirectory, torSocksPort, torControlPort);
	}

	@Override
	protected int getProcessId() {
		return Kernel32.INSTANCE.GetCurrentProcessId();
	}

	@Override
	protected String getExecutableExtension() {
		return ".exe";
	}
}

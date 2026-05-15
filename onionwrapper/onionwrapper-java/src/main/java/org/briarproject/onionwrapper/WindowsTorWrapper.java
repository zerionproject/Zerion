package org.briarproject.onionwrapper;

import com.sun.jna.platform.win32.Kernel32;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.util.concurrent.Executor;

@NotNullByDefault
public class WindowsTorWrapper extends JavaTorWrapper {

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

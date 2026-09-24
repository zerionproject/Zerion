package org.zerionproject.tor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

/**
 * The wrapper with the platform pieces stubbed: process id and update time
 * are constants, the "executables" are whatever bytes the test supplies,
 * and the file run as Tor can be replaced by a script. Installation skips
 * the executable bit so the tests behave the same on every host.
 */
class TestTorWrapper extends AbstractTorWrapper {

	static final Executor IO = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "test-io");
		t.setDaemon(true);
		return t;
	});

	@Nullable
	volatile File torScript = null;
	volatile long startTimeoutMs = START_TIMEOUT_MS;
	@Nullable
	volatile Process launched = null;

	TestTorWrapper(File dir, int socksPort, int controlPort,
			TorBinaryVerifier verifier) {
		super(IO, Runnable::run, "arm64_pie", dir, socksPort, controlPort,
				verifier);
	}

	@Override
	protected int getProcessId() {
		return 4242;
	}

	@Override
	protected long getLastUpdateTime() {
		return 0;
	}

	@Override
	protected InputStream getResourceInputStream(String name,
			String extension) {
		return new ByteArrayInputStream(("fake " + name).getBytes());
	}

	@Override
	protected void installTorExecutable() throws IOException {
		extract(getExecutableInputStream("tor"), super.getTorExecutableFile());
	}

	@Override
	protected void installLyrebirdExecutable() throws IOException {
		extract(getExecutableInputStream("lyrebird"),
				super.getLyrebirdExecutableFile());
	}

	@Override
	protected File getTorExecutableFile() {
		File s = torScript;
		return s == null ? super.getTorExecutableFile() : s;
	}

	@Override
	void waitForTorToStart(Process torProcess, long timeoutMs)
			throws InterruptedException, IOException {
		launched = torProcess;
		super.waitForTorToStart(torProcess, startTimeoutMs);
	}
}

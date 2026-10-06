package org.zerionproject.tor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

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

	TestTorWrapper(File dir, String controlSocketPath,
			TorBinaryVerifier verifier) {
		super(IO, Runnable::run, "arm64_pie", dir, 9050, 9051,
				controlSocketPath, verifier);
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

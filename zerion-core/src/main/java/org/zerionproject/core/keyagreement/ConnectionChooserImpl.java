package org.zerionproject.core.keyagreement;

import org.zerionproject.core.api.keyagreement.KeyAgreementConnection;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.system.Clock;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
@NotNullByDefault
@ThreadSafe
class ConnectionChooserImpl implements ConnectionChooser {
	private final Clock clock;
	private final Executor ioExecutor;
	private final Object lock = new Object();
	private boolean stopped = false;
	private final Queue<KeyAgreementConnection> results = new LinkedList<>();

	@Inject
	ConnectionChooserImpl(Clock clock, @IoExecutor Executor ioExecutor) {
		this.clock = clock;
		this.ioExecutor = ioExecutor;
	}

	@Override
	public void submit(Callable<KeyAgreementConnection> task) {
		ioExecutor.execute(() -> {
			try {
				KeyAgreementConnection c = task.call();
				if (c != null) addResult(c);
			} catch (Exception e) {
			}
		});
	}

	@Nullable
	@Override
	public KeyAgreementConnection poll(long timeout)
			throws InterruptedException {
		long now = clock.currentTimeMillis();
		long end = now + timeout;
		synchronized (lock) {
			while (!stopped && results.isEmpty() && now < end) {
				lock.wait(end - now);
				now = clock.currentTimeMillis();
			}
			return results.poll();
		}
	}

	@Override
	public void stop() {
		List<KeyAgreementConnection> unused;
		synchronized (lock) {
			unused = new ArrayList<>(results);
			results.clear();
			stopped = true;
			lock.notifyAll();
		}
	}

	private void addResult(KeyAgreementConnection c) {
		boolean close = false;
		synchronized (lock) {
			if (stopped) {
				close = true;
			} else {
				results.add(c);
				lock.notifyAll();
			}
		}
		if (close) {
			tryToClose(c.getConnection());
		}
	}

	private void tryToClose(DuplexTransportConnection conn) {
		try {
			conn.getReader().dispose(false, true);
			conn.getWriter().dispose(false);
		} catch (IOException e) {
		}
	}
}

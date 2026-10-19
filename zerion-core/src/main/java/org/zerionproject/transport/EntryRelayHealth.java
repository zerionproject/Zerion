package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.function.Consumer;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class EntryRelayHealth {

	static final long UNANSWERED_MS = 2 * 60_000L;

	private final Consumer<Boolean> listener;

	@GuardedBy("this")
	private boolean warned = false;
	@GuardedBy("this")
	private boolean waiting = false;
	@GuardedBy("this")
	private long waitingSince = 0;
	@GuardedBy("this")
	private int bootstrapPercentage = 0;
	@GuardedBy("this")
	private boolean failing = false;

	EntryRelayHealth(Consumer<Boolean> listener) {
		this.listener = listener;
	}

	synchronized void onWarning(long now) {
		warned = true;
		update(now);
	}

	synchronized boolean onRequest(long now) {
		boolean started = !waiting;
		if (started) {
			waiting = true;
			waitingSince = now;
		}
		update(now);
		return started;
	}

	synchronized void onAnswer(long now) {
		waiting = false;
		update(now);
	}

	synchronized void onRecheck(long now) {
		update(now);
	}

	synchronized void onBootstrap(int percentage) {
		boolean newRun = percentage < bootstrapPercentage;
		bootstrapPercentage = percentage;
		if (newRun) clear();
	}

	synchronized void clear() {
		warned = false;
		waiting = false;
		set(false);
	}

	synchronized boolean isFailing() {
		return failing;
	}

	@GuardedBy("this")
	private void update(long now) {
		set(warned && waiting && now - waitingSince >= UNANSWERED_MS);
	}

	@GuardedBy("this")
	private void set(boolean next) {
		if (next == failing) return;
		failing = next;
		listener.accept(next);
	}
}

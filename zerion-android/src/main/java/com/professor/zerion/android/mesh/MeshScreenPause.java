package com.professor.zerion.android.mesh;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class MeshScreenPause {

	static final long PAUSE_AFTER_MS = 10 * 60_000L;

	interface Radio {

		void pause();

		void resume();
	}

	interface Cancellable {

		void cancel();
	}

	interface Scheduler {

		Cancellable schedule(Runnable task, long delayMs);
	}

	private final Radio radio;
	private final Scheduler scheduler;
	private final long pauseAfterMs;

	private boolean enabled = false;
	private boolean screenOff = false;
	private boolean paused = false;
	private long generation = 0;
	@Nullable
	private Cancellable pending;

	MeshScreenPause(Radio radio, Scheduler scheduler, long pauseAfterMs) {
		this.radio = radio;
		this.scheduler = scheduler;
		this.pauseAfterMs = pauseAfterMs;
	}

	synchronized void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (enabled) {
			if (screenOff) startWaiting();
		} else {
			stopWaiting();
			wake();
		}
	}

	synchronized void onScreenOff() {
		screenOff = true;
		if (enabled) startWaiting();
	}

	synchronized void onScreenOn() {
		screenOff = false;
		stopWaiting();
		wake();
	}

	synchronized boolean isPaused() {
		return paused;
	}

	private void startWaiting() {
		if (paused || pending != null) return;
		long mine = ++generation;
		pending = scheduler.schedule(() -> fire(mine), pauseAfterMs);
	}

	private void stopWaiting() {
		generation++;
		Cancellable c = pending;
		pending = null;
		if (c != null) c.cancel();
	}

	private void wake() {
		if (!paused) return;
		paused = false;
		radio.resume();
	}

	private synchronized void fire(long mine) {
		if (mine != generation) return;
		pending = null;
		if (!enabled || !screenOff || paused) return;
		paused = true;
		radio.pause();
	}
}

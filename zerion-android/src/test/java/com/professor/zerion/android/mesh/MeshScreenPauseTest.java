package com.professor.zerion.android.mesh;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MeshScreenPauseTest {

	private final List<String> radio = new ArrayList<>();
	private final List<Task> tasks = new ArrayList<>();
	private final MeshScreenPause pause = new MeshScreenPause(
			new MeshScreenPause.Radio() {
				@Override
				public void pause() {
					radio.add("pause");
				}

				@Override
				public void resume() {
					radio.add("resume");
				}
			}, (task, delayMs) -> {
				Task t = new Task(task, delayMs);
				tasks.add(t);
				return () -> t.cancelled = true;
			}, MeshScreenPause.PAUSE_AFTER_MS);

	@Test
	public void theMeshKeepsRunningWithTheScreenOffByDefault() {
		pause.onScreenOff();
		assertTrue(tasks.isEmpty());
		assertTrue(radio.isEmpty());
	}

	@Test
	public void theMeshPausesTenMinutesAfterTheScreenGoesOff() {
		pause.setEnabled(true);
		pause.onScreenOff();
		assertEquals(1, tasks.size());
		assertEquals(10 * 60_000L, tasks.get(0).delayMs);
		assertTrue(radio.isEmpty());
		tasks.get(0).run();
		assertEquals(1, radio.size());
		assertEquals("pause", radio.get(0));
		assertTrue(pause.isPaused());
	}

	@Test
	public void turningTheScreenOnInTimeKeepsTheMeshRunning() {
		pause.setEnabled(true);
		pause.onScreenOff();
		pause.onScreenOn();
		tasks.get(0).run();
		assertTrue(radio.isEmpty());
		assertFalse(pause.isPaused());
	}

	@Test
	public void turningTheScreenOnResumesThePausedMesh() {
		pause.setEnabled(true);
		pause.onScreenOff();
		tasks.get(0).run();
		pause.onScreenOn();
		assertEquals(2, radio.size());
		assertEquals("resume", radio.get(1));
		assertFalse(pause.isPaused());
	}

	@Test
	public void switchingTheOptionOffResumesThePausedMesh() {
		pause.setEnabled(true);
		pause.onScreenOff();
		tasks.get(0).run();
		pause.setEnabled(false);
		assertEquals("resume", radio.get(radio.size() - 1));
		assertFalse(pause.isPaused());
	}

	@Test
	public void switchingTheOptionOnWhileTheScreenIsOffStartsTheWait() {
		pause.onScreenOff();
		pause.setEnabled(true);
		assertEquals(1, tasks.size());
		tasks.get(0).run();
		assertTrue(pause.isPaused());
	}

	@Test
	public void repeatedScreenOffEventsDoNotRestartTheWait() {
		pause.setEnabled(true);
		pause.onScreenOff();
		pause.onScreenOff();
		assertEquals(1, tasks.size());
	}

	private static final class Task {
		final Runnable task;
		final long delayMs;
		boolean cancelled = false;

		Task(Runnable task, long delayMs) {
			this.task = task;
			this.delayMs = delayMs;
		}

		void run() {
			if (!cancelled) task.run();
		}
	}
}

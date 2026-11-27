package com.professor.zerion.android.conversation;

import org.junit.Test;
import org.zerionproject.core.api.sync.MessageId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class SecretNoteTimersTest {

	private static final class Task {

		private final Runnable runnable;
		private final long at;

		private Task(Runnable runnable, long at) {
			this.runnable = runnable;
			this.at = at;
		}
	}

	private long now = 50_000L;
	private final List<Task> tasks = new ArrayList<>();
	private final List<MessageId> burned = new ArrayList<>();
	private final SecretNoteTimers timers = new SecretNoteTimers(
			() -> now,
			new SecretNoteTimers.Scheduler() {
				@Override
				public void schedule(Runnable task, long delayMillis) {
					tasks.add(new Task(task, now + delayMillis));
				}

				@Override
				public void cancel(Runnable task) {
					for (int i = tasks.size() - 1; i >= 0; i--) {
						if (tasks.get(i).runnable == task) tasks.remove(i);
					}
				}
			}, burned::add);

	private final MessageId a = new MessageId(getRandomId());
	private final MessageId b = new MessageId(getRandomId());
	private final MessageId c = new MessageId(getRandomId());

	private void pass(long millis) {
		long end = now + millis;
		while (true) {
			Task next = null;
			for (Task t : tasks) {
				if (t.at <= end && (next == null || t.at < next.at)) next = t;
			}
			if (next == null) break;
			tasks.remove(next);
			now = Math.max(now, next.at);
			next.runnable.run();
		}
		now = end;
	}

	@Test
	public void openingASecondNoteLeavesTheFirstCountingDown() {
		assertTrue(timers.reveal(a, "first", 5));
		pass(2_000);
		assertTrue(timers.reveal(b, "second", 5));

		assertEquals(Collections.emptyList(), burned);
		assertEquals("first", timers.content(a));
		assertEquals(3, timers.secondsLeft(a));
		assertEquals("second", timers.content(b));
		assertEquals(5, timers.secondsLeft(b));

		pass(3_000);
		assertEquals(Collections.singletonList(a), burned);
		assertNull(timers.content(a));
		assertEquals("second", timers.content(b));
		assertEquals(2, timers.secondsLeft(b));

		pass(2_000);
		assertEquals(Arrays.asList(a, b), burned);
		assertNull(timers.content(b));
	}

	@Test
	public void eachNoteKeepsItsOwnLength() {
		timers.reveal(a, "one minute", 60);
		timers.reveal(b, "ten seconds", 10);
		timers.reveal(c, "one second", 1);

		pass(1_000);
		assertEquals(Collections.singletonList(c), burned);
		pass(9_000);
		assertEquals(Arrays.asList(c, b), burned);
		assertEquals(50, timers.secondsLeft(a));
		pass(49_999);
		assertEquals(Arrays.asList(c, b), burned);
		assertEquals("one minute", timers.content(a));
		assertEquals(1, timers.secondsLeft(a));
		assertEquals(1, timers.millisToNextSecond(a));
		pass(1);
		assertEquals(Arrays.asList(c, b, a), burned);
	}

	@Test
	public void theContentIsGoneAtTheDeadlineEvenBeforeTheBurnRuns() {
		timers.reveal(a, "first", 3);
		now += 3_000;

		assertNull(timers.content(a));
		assertEquals(0, timers.secondsLeft(a));
		assertEquals(Collections.emptyList(), burned);

		pass(0);
		assertEquals(Collections.singletonList(a), burned);
	}

	@Test
	public void aLateSchedulerStillBurnsOnlyWhenTheDeadlineIsReached() {
		timers.reveal(a, "first", 3);
		Runnable early = tasks.get(0).runnable;
		tasks.clear();
		now += 1_000;
		early.run();

		assertEquals(Collections.emptyList(), burned);
		assertEquals("first", timers.content(a));
		pass(2_000);
		assertEquals(Collections.singletonList(a), burned);
	}

	@Test
	public void aNoteIsRevealedOnlyOnceAndStaysRevealedAfterItBurns() {
		assertFalse(timers.isRevealed(a));
		assertTrue(timers.reveal(a, "first", 2));
		assertFalse(timers.reveal(a, "first", 2));
		assertTrue(timers.isRevealed(a));

		pass(2_000);
		assertTrue(timers.isRevealed(a));
		assertFalse(timers.reveal(a, "first", 2));
		assertNull(timers.content(a));
		assertEquals(Collections.singletonList(a), burned);
	}

	@Test
	public void burnAllBurnsEveryCountingNoteOnceAndLeavesNoTimer() {
		timers.reveal(a, "first", 30);
		pass(1_000);
		timers.reveal(b, "second", 60);

		timers.burnAll();

		assertEquals(2, burned.size());
		assertTrue(burned.containsAll(Arrays.asList(a, b)));
		assertNull(timers.content(a));
		assertNull(timers.content(b));
		assertTrue(tasks.isEmpty());
		timers.burnAll();
		pass(120_000);
		assertEquals(2, burned.size());
		assertFalse(timers.isRevealed(c));
	}

	@Test
	public void theCountdownTicksOnTheSecond() {
		timers.reveal(a, "first", 10);
		assertEquals(10, timers.secondsLeft(a));
		assertEquals(1_000, timers.millisToNextSecond(a));
		pass(250);
		assertEquals(10, timers.secondsLeft(a));
		assertEquals(750, timers.millisToNextSecond(a));
		pass(750);
		assertEquals(9, timers.secondsLeft(a));
		assertEquals(0, timers.millisToNextSecond(b));
		assertEquals(0, timers.secondsLeft(b));
	}

	@Test
	public void forgettingANoteDropsItWithoutABurn() {
		timers.reveal(a, "first", 30);
		timers.reveal(b, "second", 30);

		assertEquals(Collections.singletonList(a),
				new ArrayList<>(timers.forget(Arrays.asList(a, c))));

		assertNull(timers.content(a));
		assertTrue(timers.isRevealed(a));
		assertFalse(timers.reveal(a, "first", 30));
		assertEquals("second", timers.content(b));
		assertEquals(1, tasks.size());

		assertEquals(Collections.singletonList(b),
				new ArrayList<>(timers.forgetAll()));
		assertNull(timers.content(b));
		assertTrue(tasks.isEmpty());
		pass(60_000);
		assertEquals(Collections.emptyList(), burned);
		assertTrue(timers.forget(Collections.singletonList(a)).isEmpty());
	}
}

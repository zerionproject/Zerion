package com.professor.zerion.android.conversation;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.sync.MessageId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
@NotNullByDefault
class SecretNoteTimers {

	interface Clock {
		long now();
	}

	interface Scheduler {
		void schedule(Runnable task, long delayMillis);

		void cancel(Runnable task);
	}

	interface Burner {
		void burn(MessageId id);
	}

	private static final class Reveal {

		private final long deadline;
		private final Runnable task;
		@Nullable
		private String content;

		private Reveal(long deadline, String content, Runnable task) {
			this.deadline = deadline;
			this.content = content;
			this.task = task;
		}
	}

	private final Clock clock;
	private final Scheduler scheduler;
	private final Burner burner;
	private final Map<MessageId, Reveal> counting = new HashMap<>();
	private final Set<MessageId> burned = new HashSet<>();

	SecretNoteTimers(Clock clock, Scheduler scheduler, Burner burner) {
		this.clock = clock;
		this.scheduler = scheduler;
		this.burner = burner;
	}

	static SecretNoteTimers onMainThread(Burner burner) {
		Handler handler = new Handler(Looper.getMainLooper());
		return new SecretNoteTimers(SystemClock::elapsedRealtime,
				new Scheduler() {
					@Override
					public void schedule(Runnable task, long delayMillis) {
						handler.postDelayed(task, delayMillis);
					}

					@Override
					public void cancel(Runnable task) {
						handler.removeCallbacks(task);
					}
				}, burner);
	}

	boolean reveal(MessageId id, String content, int seconds) {
		if (isRevealed(id)) return false;
		long length = Math.max(0, seconds) * 1000L;
		Reveal r = new Reveal(clock.now() + length, content,
				() -> expire(id));
		counting.put(id, r);
		scheduler.schedule(r.task, length);
		return true;
	}

	boolean isRevealed(MessageId id) {
		return counting.containsKey(id) || burned.contains(id);
	}

	@Nullable
	String content(MessageId id) {
		Reveal r = counting.get(id);
		return r != null && millisLeft(id) > 0 ? r.content : null;
	}

	int secondsLeft(MessageId id) {
		long left = millisLeft(id);
		return left <= 0 ? 0 : (int) ((left + 999) / 1000);
	}

	long millisToNextSecond(MessageId id) {
		long left = millisLeft(id);
		if (left <= 0) return 0;
		long part = left % 1000;
		return part == 0 ? 1000 : part;
	}

	Collection<MessageId> forget(Collection<MessageId> ids) {
		List<MessageId> forgotten = new ArrayList<>();
		for (MessageId id : ids) {
			Reveal r = counting.remove(id);
			if (r == null) continue;
			scheduler.cancel(r.task);
			r.content = null;
			burned.add(id);
			forgotten.add(id);
		}
		return forgotten;
	}

	Collection<MessageId> forgetAll() {
		return forget(new ArrayList<>(counting.keySet()));
	}

	void burnAll() {
		for (MessageId id : new ArrayList<>(counting.keySet())) burn(id);
	}

	private long millisLeft(MessageId id) {
		Reveal r = counting.get(id);
		return r == null ? 0 : r.deadline - clock.now();
	}

	private void expire(MessageId id) {
		Reveal r = counting.get(id);
		if (r == null) return;
		long left = r.deadline - clock.now();
		if (left > 0) {
			scheduler.schedule(r.task, left);
			return;
		}
		burn(id);
	}

	private void burn(MessageId id) {
		Reveal r = counting.remove(id);
		if (r == null) return;
		scheduler.cancel(r.task);
		r.content = null;
		burned.add(id);
		burner.burn(id);
	}
}

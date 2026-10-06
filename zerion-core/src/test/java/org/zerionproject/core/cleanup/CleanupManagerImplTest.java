package org.zerionproject.core.cleanup;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.cleanup.event.CleanupTimerStartedEvent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.DbClosedException;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.test.BrambleTestCase;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import static java.util.Collections.singletonList;
import static org.zerionproject.core.api.cleanup.CleanupManager.BATCH_DELAY_MS;
import static org.zerionproject.core.api.db.DatabaseComponent.NO_CLEANUP_DEADLINE;
import static org.zerionproject.core.cleanup.CleanupManagerImpl.RETRY_DELAY_MS;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@NotNullByDefault
public class CleanupManagerImplTest extends BrambleTestCase {

	private static final ClientId CLIENT =
			new ClientId("org.zerionproject.test.cleanup");
	private static final ClientId NO_HOOK =
			new ClientId("org.zerionproject.test.nohook");

	private final PriorityQueue<Task> tasks = new PriorityQueue<>();
	private final Map<GroupId, ClientId> groups = new LinkedHashMap<>();
	private final Set<GroupId> failing = new HashSet<>();
	private final Set<GroupId> erring = new HashSet<>();
	private final Map<MessageId, Long> deletedAt = new HashMap<>();
	private final Map<MessageId, Long> removedWithoutHook = new HashMap<>();
	private final Map<GroupId, List<List<MessageId>>> handedToHook =
			new HashMap<>();
	private Map<MessageId, Timer> timers = new LinkedHashMap<>();
	private long now = 1_700_000_000_000L;
	private long sequence = 0;
	private boolean rejecting = false;
	private boolean closed = false;
	private long expiredReads = 0;
	private long rowsRead = 0;
	private CleanupManagerImpl manager;

	@Test
	public void anErrorInOneGroupsHookHoldsBackNoOtherGroupNorLaterRuns()
			throws Exception {
		start();
		GroupId broken = group(CLIENT);
		GroupId healthy = group(CLIENT);
		erring.add(broken);
		long start = now;
		MessageId stuck = timer(broken, 1_000);
		MessageId expiring = timer(healthy, 1_000);
		MessageId later = timer(healthy, 60_000);

		runUntil(start + 1_000 + BATCH_DELAY_MS);

		assertEquals(Long.valueOf(start + 1_000 + BATCH_DELAY_MS),
				deletedAt.get(expiring));
		assertFalse(deletedAt.containsKey(stuck));
		assertTrue(timers.containsKey(stuck));

		runUntil(start + 60_000 + BATCH_DELAY_MS);

		assertEquals(Long.valueOf(start + 60_000 + BATCH_DELAY_MS),
				deletedAt.get(later));
		assertFalse(deletedAt.containsKey(stuck));

		erring.remove(broken);
		runUntil(start + 60_000 + 2 * RETRY_DELAY_MS + 3 * BATCH_DELAY_MS);

		assertTrue(deletedAt.containsKey(stuck));
		List<List<MessageId>> attempts = handedToHook.get(broken);
		assertEquals("the broken group is tried once, then once more after"
				+ " the retry delay", 2, attempts.size());
		for (List<MessageId> ids : attempts) {
			assertEquals(singletonList(stuck), ids);
		}
		assertTrue(timers.isEmpty());
	}

	@Test
	public void aFailingGroupIsTriedAgainOnlyAfterTheRetryDelay()
			throws Exception {
		start();
		GroupId broken = group(CLIENT);
		GroupId busy = group(CLIENT);
		failing.add(broken);
		long start = now;
		timer(broken, 1_000);
		for (int i = 1; i <= 50; i++) timer(busy, 1_000 + i * 1_000L);

		runUntil(start + 1_000 + RETRY_DELAY_MS - 1);

		assertEquals("the failing group was tried at every run the other"
						+ " group's deadlines brought",
				1, handedToHook.get(broken).size());
		assertTrue("the other group's runs went on",
				handedToHook.get(busy).size() > 10);
		for (Timer t : timers.values()) {
			assertEquals(broken, t.group);
		}

		runUntil(start + 1_000 + RETRY_DELAY_MS + 2 * BATCH_DELAY_MS);

		assertEquals(2, handedToHook.get(broken).size());
	}

	@Test
	public void expiredMessagesOfAGroupWithoutAHookAreDeletedOnce()
			throws Exception {
		start();
		GroupId orphan = group(NO_HOOK);
		long start = now;
		MessageId m = timer(orphan, 1_000);

		runUntil(start + 1_000 + BATCH_DELAY_MS);

		assertEquals(Long.valueOf(start + 1_000 + BATCH_DELAY_MS),
				removedWithoutHook.get(m));
		assertFalse(timers.containsKey(m));
		int tasksBefore = tasks.size();
		runUntil(start + 10 * RETRY_DELAY_MS);
		assertTrue("nothing is retried once the messages are gone",
				tasks.size() <= tasksBefore);
	}

	@Test
	public void aRunAfterTheDatabaseIsClosedAtShutdownThrowsNothing()
			throws Exception {
		start();
		GroupId g = group(CLIENT);
		long start = now;
		MessageId m = timer(g, 1_000);
		rejecting = true;
		closed = true;

		runUntil(start + 1_000 + BATCH_DELAY_MS);

		assertTrue(tasks.isEmpty());
		assertTrue(timers.containsKey(m));
		assertFalse(deletedAt.containsKey(m));
	}

	@Test
	public void aRejectedScheduleThrowsNothingAndBlocksNoLaterTimer()
			throws Exception {
		start();
		GroupId broken = group(CLIENT);
		GroupId healthy = group(CLIENT);
		failing.add(broken);
		long start = now;
		timer(broken, 1_000);
		MessageId expiring = timer(healthy, 1_000);
		timer(healthy, 60_000);
		rejecting = true;

		runUntil(start + 1_000 + BATCH_DELAY_MS);

		assertEquals(Long.valueOf(start + 1_000 + BATCH_DELAY_MS),
				deletedAt.get(expiring));
		assertTrue(tasks.isEmpty());

		rejecting = false;
		failing.clear();
		MessageId next = timer(healthy, 5_000);
		runUntil(start + 6_000 + 2 * BATCH_DELAY_MS);

		assertEquals(Long.valueOf(start + 6_000 + 2 * BATCH_DELAY_MS),
				deletedAt.get(next));
		runUntil(start + 1_000 + RETRY_DELAY_MS + 2 * BATCH_DELAY_MS);
		assertTrue(timers.isEmpty());
	}

	@Test
	public void eachRunReadsEachExpiredMessageOnce() throws Exception {
		int groupCount = 400;
		int perGroup = 50;
		start();
		runUntil(now + 5_000);
		List<GroupId> gs = new ArrayList<>();
		for (int i = 0; i < groupCount; i++) gs.add(group(CLIENT));
		long start = now;
		for (GroupId g : gs) {
			for (int k = 0; k < perGroup; k++) timer(g, 10_000);
		}
		expiredReads = 0;
		rowsRead = 0;

		runUntil(start + 20_000);

		long expired = (long) groupCount * perGroup;
		String reported = groupCount + " groups, " + expired
				+ " expired messages: " + expiredReads
				+ " reads of expired messages returned " + rowsRead
				+ " rows";
		assertTrue(reported, timers.isEmpty());
		assertEquals(expired, deletedAt.size());
		for (long at : deletedAt.values()) {
			assertEquals(start + 10_000 + BATCH_DELAY_MS, at);
		}
		assertTrue(reported, rowsRead <= expired + groupCount);
		assertTrue(reported, expiredReads <= groupCount + 1);
	}

	@Test
	public void expiredMessagesAreDeletedOnTimeAndNeverEarly()
			throws Exception {
		Random random = new Random(20260930L);
		for (int trial = 0; trial < 200; trial++) {
			reset();
			start();
			List<GroupId> healthy = new ArrayList<>();
			List<GroupId> broken = new ArrayList<>();
			for (int i = 0; i < 1 + random.nextInt(6); i++) {
				healthy.add(group(CLIENT));
			}
			for (int i = 0; i < 1 + random.nextInt(3); i++) {
				int kind = random.nextInt(3);
				GroupId g = group(kind == 0 ? NO_HOOK : CLIENT);
				if (kind == 1) failing.add(g);
				if (kind == 2) erring.add(g);
				broken.add(g);
			}
			Map<MessageId, Long> due = new HashMap<>();
			Map<MessageId, GroupId> of = new HashMap<>();
			long end = now + 30 * 60_000L;
			long heal = now + 10 * 60_000L;
			long healedAt = end;
			while (now < end) {
				long step = random.nextInt(4) == 0 ? random.nextInt(3)
						: random.nextInt(40_000);
				runUntil(now + step);
				if (now >= heal && healedAt == end) {
					failing.clear();
					erring.clear();
					healedAt = now;
				}
				GroupId g = random.nextInt(10) < 7
						? healthy.get(random.nextInt(healthy.size()))
						: broken.get(random.nextInt(broken.size()));
				long duration = random.nextInt(5) == 0 ? random.nextInt(2)
						: random.nextInt(3) == 0 ? 300_000L
						: random.nextInt(120_000);
				MessageId m = timer(g, duration);
				due.put(m, now + duration);
				of.put(m, g);
			}
			runUntil(end + 60 * 60_000L);
			for (Map.Entry<MessageId, Long> e : due.entrySet()) {
				GroupId g = of.get(e.getKey());
				Long at = deletedAt.get(e.getKey());
				if (groups.get(g).equals(NO_HOOK)) {
					assertNull(at);
					at = removedWithoutHook.get(e.getKey());
					String reported = "trial " + trial + ", due "
							+ e.getValue() + ", removed " + at;
					assertTrue(reported, at != null);
					assertTrue(reported, at >= e.getValue());
					assertTrue(reported,
							at <= e.getValue() + BATCH_DELAY_MS + 1);
					continue;
				}
				String reported = "trial " + trial + ", due " + e.getValue()
						+ ", deleted " + at;
				assertTrue(reported, at != null);
				assertTrue(reported, at >= e.getValue());
				if (healthy.contains(g)) {
					assertTrue(reported,
							at <= e.getValue() + BATCH_DELAY_MS + 1);
				} else {
					long from = Math.max(healedAt, e.getValue());
					assertTrue(reported, at <= from + RETRY_DELAY_MS
							+ BATCH_DELAY_MS + 1);
				}
			}
		}
	}

	private void reset() {
		tasks.clear();
		groups.clear();
		failing.clear();
		erring.clear();
		deletedAt.clear();
		removedWithoutHook.clear();
		handedToHook.clear();
		timers = new LinkedHashMap<>();
		rejecting = false;
		closed = false;
	}

	private void start() {
		DatabaseComponent db = (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					try {
						return store(method.getName(),
								args == null ? new Object[0] : args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		TaskScheduler scheduler = new TaskScheduler() {
			@Override
			public Cancellable schedule(Runnable task, Executor executor,
					long delay, TimeUnit unit) {
				if (rejecting) throw new RejectedExecutionException();
				tasks.add(new Task(now + unit.toMillis(delay), sequence++,
						() -> executor.execute(task)));
				return () -> {
				};
			}

			@Override
			public Cancellable scheduleWithFixedDelay(Runnable task,
					Executor executor, long delay, long interval,
					TimeUnit unit) {
				throw new UnsupportedOperationException();
			}
		};
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return now;
			}

			@Override
			public void sleep(long milliseconds) {
			}
		};
		manager = new CleanupManagerImpl(Runnable::run, db, scheduler, clock);
		manager.registerCleanupHook(CLIENT, 0, (txn, g, ids) -> {
			handedToHook.computeIfAbsent(g, k -> new ArrayList<>())
					.add(new ArrayList<>(ids));
			if (erring.contains(g)) throw new HookError();
			if (failing.contains(g)) throw new DbException();
			for (MessageId m : ids) {
				if (timers.remove(m) != null) deletedAt.put(m, now);
			}
		});
		manager.startService();
		runUntil(now + 5_000);
	}

	@Nullable
	private Object store(String name, Object[] a) throws Exception {
		if (closed) throw new DbClosedException();
		switch (name) {
			case "transaction": {
				Map<MessageId, Timer> saved = copyOfTimers();
				try {
					((DbRunnable<?>) a[1]).run(new Transaction(this,
							(Boolean) a[0]));
				} catch (Exception | Error e) {
					timers = saved;
					throw e;
				}
				return null;
			}
			case "transactionWithResult": {
				Map<MessageId, Timer> saved = copyOfTimers();
				try {
					return ((DbCallable<?, ?>) a[1]).call(new Transaction(
							this, (Boolean) a[0]));
				} catch (Exception | Error e) {
					timers = saved;
					throw e;
				}
			}
			case "getMessagesToDelete": {
				expiredReads++;
				Map<GroupId, Collection<MessageId>> expired =
						new LinkedHashMap<>();
				for (Map.Entry<MessageId, Timer> e : timers.entrySet()) {
					Timer t = e.getValue();
					if (t.deadline == null || t.deadline > now) continue;
					if (a.length == 2 && !t.group.equals(a[1])) continue;
					rowsRead++;
					expired.computeIfAbsent(t.group,
							k -> new ArrayList<>()).add(e.getKey());
				}
				if (a.length == 1) return expired;
				Collection<MessageId> ids = expired.get((GroupId) a[1]);
				return ids == null ? new ArrayList<MessageId>() : ids;
			}
			case "getGroupsWithMessagesToDelete": {
				Set<GroupId> found = new LinkedHashSet<>();
				for (Timer t : timers.values()) {
					if (t.deadline != null && t.deadline <= now) {
						found.add(t.group);
					}
				}
				rowsRead += found.size();
				return new ArrayList<>(found);
			}
			case "getNextCleanupDeadline": {
				long next = NO_CLEANUP_DEADLINE;
				for (Timer t : timers.values()) {
					if (t.deadline == null) continue;
					if (a.length == 2 && t.deadline <= (Long) a[1]) continue;
					if (next == NO_CLEANUP_DEADLINE || t.deadline < next) {
						next = t.deadline;
					}
				}
				return next;
			}
			case "stopCleanupTimer": {
				Timer t = timers.get((MessageId) a[1]);
				if (t != null) t.deadline = null;
				return null;
			}
			case "removeMessage": {
				if (timers.remove((MessageId) a[1]) != null) {
					removedWithoutHook.put((MessageId) a[1], now);
				}
				return null;
			}
			case "getGroup": {
				GroupId g = (GroupId) a[1];
				return new Group(g, groups.get(g), 0, new byte[0]);
			}
			case "hashCode":
				return System.identityHashCode(this);
			case "toString":
				return "store";
			default:
				throw new UnsupportedOperationException(name);
		}
	}

	private Map<MessageId, Timer> copyOfTimers() {
		Map<MessageId, Timer> copy = new LinkedHashMap<>();
		for (Map.Entry<MessageId, Timer> e : timers.entrySet()) {
			copy.put(e.getKey(), new Timer(e.getValue().group,
					e.getValue().deadline));
		}
		return copy;
	}

	private GroupId group(ClientId c) {
		GroupId g = new GroupId(getRandomId());
		groups.put(g, c);
		return g;
	}

	private MessageId timer(GroupId g, long duration) {
		MessageId m = new MessageId(getRandomId());
		long deadline = now + duration;
		timers.put(m, new Timer(g, deadline));
		manager.eventOccurred(new CleanupTimerStartedEvent(m, deadline));
		return m;
	}

	private void runUntil(long time) {
		while (!tasks.isEmpty() && tasks.peek().at <= time) {
			Task t = tasks.poll();
			if (t.at > now) now = t.at;
			t.run.run();
		}
		if (time > now) now = time;
	}

	private static final class Timer {

		private final GroupId group;
		@Nullable
		private Long deadline;

		private Timer(GroupId group, @Nullable Long deadline) {
			this.group = group;
			this.deadline = deadline;
		}
	}

	private static final class Task implements Comparable<Task> {

		private final long at;
		private final long sequence;
		private final Runnable run;

		private Task(long at, long sequence, Runnable run) {
			this.at = at;
			this.sequence = sequence;
			this.run = run;
		}

		@Override
		public int compareTo(Task o) {
			if (at != o.at) return Long.compare(at, o.at);
			return Long.compare(sequence, o.sequence);
		}
	}

	private static final class HookError extends Error {
	}
}

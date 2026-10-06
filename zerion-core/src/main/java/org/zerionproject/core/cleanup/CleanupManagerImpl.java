package org.zerionproject.core.cleanup;

import org.zerionproject.core.api.cleanup.CleanupHook;
import org.zerionproject.core.api.cleanup.CleanupManager;
import org.zerionproject.core.api.cleanup.event.CleanupTimerStartedEvent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.versioning.ClientMajorVersion;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.zerionproject.core.api.db.DatabaseComponent.NO_CLEANUP_DEADLINE;
@ThreadSafe
@NotNullByDefault
class CleanupManagerImpl implements CleanupManager, Service, EventListener {

	static final long RETRY_DELAY_MS = MINUTES.toMillis(1);

	private final Executor dbExecutor;
	private final DatabaseComponent db;
	private final TaskScheduler taskScheduler;
	private final Clock clock;
	private final Map<ClientMajorVersion, CleanupHook> hooks =
			new ConcurrentHashMap<>();
	private final Object lock = new Object();

	@GuardedBy("lock")
	private final Set<CleanupTask> pending = new HashSet<>();

	private final Map<GroupId, Long> retryAt = new ConcurrentHashMap<>();

	@Inject
	CleanupManagerImpl(@DatabaseExecutor Executor dbExecutor,
			DatabaseComponent db, TaskScheduler taskScheduler, Clock clock) {
		this.dbExecutor = dbExecutor;
		this.db = db;
		this.taskScheduler = taskScheduler;
		this.clock = clock;
	}

	@Override
	public void registerCleanupHook(ClientId c, int majorVersion,
			CleanupHook hook) {
		hooks.put(new ClientMajorVersion(c, majorVersion), hook);
	}

	@Override
	public void startService() {
		maybeScheduleTask(clock.currentTimeMillis());
	}

	@Override
	public void stopService() {
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof CleanupTimerStartedEvent) {
			CleanupTimerStartedEvent a = (CleanupTimerStartedEvent) e;
			maybeScheduleTask(a.getCleanupDeadline());
		}
	}

	private void maybeScheduleTask(long deadline) {
		synchronized (lock) {
			for (CleanupTask task : pending) {
				if (task.deadline <= deadline) return;
			}
			CleanupTask task = new CleanupTask(deadline);
			pending.add(task);
			scheduleTask(task);
		}
	}

	private void scheduleTask(CleanupTask task) {
		long now = clock.currentTimeMillis();
		long delay = max(0, task.deadline - now + BATCH_DELAY_MS);
		try {
			taskScheduler.schedule(
					() -> deleteMessagesAndScheduleNextTask(task),
					dbExecutor, delay, MILLISECONDS);
		} catch (RejectedExecutionException e) {
			pending.remove(task);
		}
	}

	private void deleteMessagesAndScheduleNextTask(CleanupTask task) {
		synchronized (lock) {
			pending.remove(task);
		}
		long now = clock.currentTimeMillis();
		boolean retry = true;
		try {
			Collection<GroupId> groups = db.transactionWithResult(true,
					txn -> db.getGroupsWithMessagesToDelete(txn));
			retryAt.keySet().retainAll(new HashSet<>(groups));
			boolean held = false;
			for (GroupId g : groups) {
				Long at = retryAt.get(g);
				if (at != null && at > now) {
					held = true;
					continue;
				}
				if (deleteMessages(g)) {
					retryAt.remove(g);
				} else {
					retryAt.put(g, now + RETRY_DELAY_MS);
					held = true;
				}
			}
			boolean skipExpired = held;
			long deadline = db.transactionWithResult(true, txn ->
					skipExpired ? db.getNextCleanupDeadline(txn, now)
							: db.getNextCleanupDeadline(txn));
			if (deadline != NO_CLEANUP_DEADLINE) {
				maybeScheduleTask(deadline);
			}
			if (held) {
				long next = Long.MAX_VALUE;
				for (long at : retryAt.values()) next = Math.min(next, at);
				if (next != Long.MAX_VALUE) maybeScheduleTask(next);
			}
			retry = false;
		} catch (DbException | RuntimeException e) {
		} finally {
			if (retry) {
				maybeScheduleTask(clock.currentTimeMillis() + RETRY_DELAY_MS);
			}
		}
	}

	private boolean deleteMessages(GroupId g) {
		try {
			db.transaction(false, txn -> deleteMessages(txn, g));
			return true;
		} catch (DbException | RuntimeException | Error e) {
			return false;
		}
	}

	private void deleteMessages(Transaction txn, GroupId groupId)
			throws DbException {
		Collection<MessageId> messageIds = db.getMessagesToDelete(txn, groupId);
		if (messageIds.isEmpty()) return;
		for (MessageId m : messageIds) db.stopCleanupTimer(txn, m);
		Group group = db.getGroup(txn, groupId);
		ClientMajorVersion cv = new ClientMajorVersion(group.getClientId(),
				group.getMajorVersion());
		CleanupHook hook = hooks.get(cv);
		if (hook == null) {
			for (MessageId m : messageIds) {
				try {
					db.removeMessage(txn, m);
				} catch (NoSuchMessageException e) {
				}
			}
			return;
		}
		hook.deleteMessages(txn, groupId, messageIds);
	}

	private static class CleanupTask {

		private final long deadline;

		private CleanupTask(long deadline) {
			this.deadline = deadline;
		}
	}
}

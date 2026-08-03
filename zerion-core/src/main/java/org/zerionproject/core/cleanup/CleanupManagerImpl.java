package org.zerionproject.core.cleanup;

import org.zerionproject.core.api.cleanup.CleanupHook;
import org.zerionproject.core.api.cleanup.CleanupManager;
import org.zerionproject.core.api.cleanup.event.CleanupTimerStartedEvent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
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
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.api.db.DatabaseComponent.NO_CLEANUP_DEADLINE;
@ThreadSafe
@NotNullByDefault
class CleanupManagerImpl implements CleanupManager, Service, EventListener {
	private final Executor dbExecutor;
	private final DatabaseComponent db;
	private final TaskScheduler taskScheduler;
	private final Clock clock;
	private final Map<ClientMajorVersion, CleanupHook> hooks =
			new ConcurrentHashMap<>();
	private final Object lock = new Object();

	@GuardedBy("lock")
	private final Set<CleanupTask> pending = new HashSet<>();

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
		taskScheduler.schedule(() -> deleteMessagesAndScheduleNextTask(task),
				dbExecutor, delay, MILLISECONDS);
	}

	private void deleteMessagesAndScheduleNextTask(CleanupTask task) {
		try {
			synchronized (lock) {
				pending.remove(task);
			}
			long deadline = db.transactionWithResult(false, txn -> {
				deleteMessages(txn);
				return db.getNextCleanupDeadline(txn);
			});
			if (deadline != NO_CLEANUP_DEADLINE) {
				maybeScheduleTask(deadline);
			}
		} catch (DbException e) {
		}
	}

	private void deleteMessages(Transaction txn) throws DbException {
		Map<GroupId, Collection<MessageId>> ids = db.getMessagesToDelete(txn);
		for (Entry<GroupId, Collection<MessageId>> e : ids.entrySet()) {
			GroupId groupId = e.getKey();
			Collection<MessageId> messageIds = e.getValue();
			for (MessageId m : messageIds) db.stopCleanupTimer(txn, m);
			Group group = db.getGroup(txn, groupId);
			ClientMajorVersion cv = new ClientMajorVersion(group.getClientId(),
					group.getMajorVersion());
			CleanupHook hook = hooks.get(cv);
			if (hook == null) {
				throw new IllegalStateException("No cleanup hook for " + cv);
			}
			hook.deleteMessages(txn, groupId, messageIds);
		}
	}

	private static class CleanupTask {

		private final long deadline;

		private CleanupTask(long deadline) {
			this.deadline = deadline;
		}
	}
}

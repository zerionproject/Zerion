package org.briarproject.bramble.lifecycle;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DataTooNewException;
import org.briarproject.bramble.api.db.DataTooOldException;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.MigrationFailedException;
import org.briarproject.bramble.api.db.MigrationListener;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.lifecycle.Service;
import org.briarproject.bramble.api.lifecycle.ServiceException;
import org.briarproject.bramble.api.lifecycle.event.LifecycleEvent;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.COMPACTING_DATABASE;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.CREATED;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.MIGRATING_DATABASE;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.RUNNING;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.STARTING;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.STARTING_SERVICES;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.STOPPED;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.STOPPING;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult.ALREADY_RUNNING;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult.CLOCK_ERROR;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult.DATA_TOO_NEW_ERROR;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult.DATA_TOO_OLD_ERROR;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult.DB_ERROR;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult.MIGRATION_FAILED;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult.SERVICE_ERROR;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult.SUCCESS;
import static org.briarproject.bramble.api.system.Clock.MAX_REASONABLE_TIME_MS;
import static org.briarproject.bramble.api.system.Clock.MIN_REASONABLE_TIME_MS;

@ThreadSafe
@NotNullByDefault
class LifecycleManagerImpl implements LifecycleManager, MigrationListener {

	private final DatabaseComponent db;
	private final EventBus eventBus;
	private final Clock clock;
	private final List<Service> services;
	private final List<OpenDatabaseHook> openDatabaseHooks;
	private final List<ExecutorService> executors;
	private final CountDownLatch dbLatch = new CountDownLatch(1);
	private final CountDownLatch startupLatch = new CountDownLatch(1);
	private final CountDownLatch shutdownLatch = new CountDownLatch(1);
	private final AtomicReference<LifecycleState> state =
			new AtomicReference<>(CREATED);

	@Inject
	LifecycleManagerImpl(DatabaseComponent db, EventBus eventBus,
			Clock clock) {
		this.db = db;
		this.eventBus = eventBus;
		this.clock = clock;
		services = new CopyOnWriteArrayList<>();
		openDatabaseHooks = new CopyOnWriteArrayList<>();
		executors = new CopyOnWriteArrayList<>();
	}

	@Override
	public void registerService(Service s) {
		services.add(s);
	}

	@Override
	public void registerOpenDatabaseHook(OpenDatabaseHook hook) {
		openDatabaseHooks.add(hook);
	}

	@Override
	public void registerForShutdown(ExecutorService e) {
		executors.add(e);
	}

	@Override
	public StartResult startServices(SecretKey dbKey) {
		if (!state.compareAndSet(CREATED, STARTING)) {
			return ALREADY_RUNNING;
		}
		long now = clock.currentTimeMillis();
		if (now < MIN_REASONABLE_TIME_MS || now > MAX_REASONABLE_TIME_MS) {
			return CLOCK_ERROR;
		}
		try {
			db.open(dbKey, this);

			db.transaction(false, txn -> {
				db.removeTemporaryMessages(txn);
				for (OpenDatabaseHook hook : openDatabaseHooks) {
					hook.onDatabaseOpened(txn);
				}
			});

			state.set(STARTING_SERVICES);
			dbLatch.countDown();
			eventBus.broadcast(new LifecycleEvent(STARTING_SERVICES));

			for (Service s : services) {
				s.startService();
			}

			state.set(RUNNING);
			startupLatch.countDown();
			eventBus.broadcast(new LifecycleEvent(RUNNING));
			return SUCCESS;
		} catch (MigrationFailedException e) {
			return MIGRATION_FAILED;
		} catch (DataTooOldException e) {
			return DATA_TOO_OLD_ERROR;
		} catch (DataTooNewException e) {
			return DATA_TOO_NEW_ERROR;
		} catch (DbException e) {
			return DB_ERROR;
		} catch (ServiceException e) {
			return SERVICE_ERROR;
		}
	}

	@Override
	public void onDatabaseMigration() {
		state.set(MIGRATING_DATABASE);
		eventBus.broadcast(new LifecycleEvent(MIGRATING_DATABASE));
	}

	@Override
	public void onDatabaseCompaction() {
		state.set(COMPACTING_DATABASE);
		eventBus.broadcast(new LifecycleEvent(COMPACTING_DATABASE));
	}

	@Override
	public void stopServices() {
		if (!state.compareAndSet(RUNNING, STOPPING)) {
			return;
		}
		eventBus.broadcast(new LifecycleEvent(STOPPING));
		for (Service s : services) {
			try {
				s.stopService();
			} catch (ServiceException e) {
			}
		}
		for (ExecutorService e : executors) {
			e.shutdownNow();
		}
		try {
			db.close();
		} catch (DbException e) {
		}
		state.set(STOPPED);
		shutdownLatch.countDown();
		eventBus.broadcast(new LifecycleEvent(STOPPED));
	}

	@Override
	public void waitForDatabase() throws InterruptedException {
		dbLatch.await();
	}

	@Override
	public void waitForStartup() throws InterruptedException {
		startupLatch.await();
	}

	@Override
	public void waitForShutdown() throws InterruptedException {
		shutdownLatch.await();
	}

	@Override
	public LifecycleState getLifecycleState() {
		return state.get();
	}
}

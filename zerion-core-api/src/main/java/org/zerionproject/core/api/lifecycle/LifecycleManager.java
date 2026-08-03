package org.zerionproject.core.api.lifecycle;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.Wakeful;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.ExecutorService;

@NotNullByDefault
public interface LifecycleManager {

	enum StartResult {
		ALREADY_RUNNING,
		CLOCK_ERROR,
		DB_ERROR,
		DATA_TOO_OLD_ERROR,
		DATA_TOO_NEW_ERROR,
		SERVICE_ERROR,
		SUCCESS
	}

	enum LifecycleState {

		CREATED,
		STARTING,
		MIGRATING_DATABASE,
		COMPACTING_DATABASE,
		STARTING_SERVICES,
		RUNNING,
		STOPPING,
		STOPPED;

		public boolean isAfter(LifecycleState state) {
			return ordinal() > state.ordinal();
		}
	}

	void registerOpenDatabaseHook(OpenDatabaseHook hook);

	void registerService(Service s);

	void registerForShutdown(ExecutorService e);

	@Wakeful
	StartResult startServices(SecretKey dbKey);

	@Wakeful
	void stopServices();

	void waitForDatabase() throws InterruptedException;

	void waitForStartup() throws InterruptedException;

	void waitForShutdown() throws InterruptedException;

	LifecycleState getLifecycleState();

	interface OpenDatabaseHook {

		@Wakeful
		void onDatabaseOpened(Transaction txn) throws DbException;
	}
}
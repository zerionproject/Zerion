package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventExecutor;
import org.zerionproject.core.api.lifecycle.ShutdownManager;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.system.Clock;

import java.sql.Connection;
import java.util.concurrent.Executor;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class DatabaseModule {

	@Provides
	@Singleton
	Database<Connection> provideDatabase(DatabaseConfig config,
			MessageFactory messageFactory, Clock clock) {
		return new HyperSqlDatabase(config, messageFactory, clock);
	}

	@Provides
	@Singleton
	DatabaseComponent provideDatabaseComponent(Database<Connection> db,
			EventBus eventBus, @EventExecutor Executor eventExecutor,
			ShutdownManager shutdownManager) {
		return new DatabaseComponentImpl<>(db, Connection.class, eventBus,
				eventExecutor, shutdownManager);
	}

	@Provides
	TransactionManager provideTransactionManager(DatabaseComponent db) {
		return db;
	}
}

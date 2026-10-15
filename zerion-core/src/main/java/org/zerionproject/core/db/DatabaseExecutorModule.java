package org.zerionproject.core.db;

import org.zerionproject.core.TimeLoggingExecutor;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static java.util.concurrent.TimeUnit.SECONDS;

@Module
public class DatabaseExecutorModule {

	public static class EagerSingletons {
		@Inject
		@DatabaseExecutor
		ExecutorService executorService;
		@Inject
		DeletedContentPurger deletedContentPurger;
	}

	@Provides
	@Singleton
	@DatabaseExecutor
	ExecutorService provideDatabaseExecutorService(
			LifecycleManager lifecycleManager, ThreadFactory threadFactory) {
		BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
		RejectedExecutionHandler policy =
				new ThreadPoolExecutor.DiscardPolicy();
		ExecutorService databaseExecutor = new TimeLoggingExecutor(
				"DatabaseExecutor", 0, 1, 60, SECONDS, queue, threadFactory,
				policy);
		lifecycleManager.registerForShutdown(databaseExecutor);
		return databaseExecutor;
	}

	@Provides
	@Singleton
	@DatabaseExecutor
	Executor provideDatabaseExecutor(
			@DatabaseExecutor ExecutorService dbExecutor) {
		return dbExecutor;
	}

	@Provides
	@Singleton
	DeletedContentPurger provideDeletedContentPurger(DatabaseComponent db,
			@DatabaseExecutor Executor dbExecutor, TaskScheduler taskScheduler,
			Clock clock, EventBus eventBus) {
		DeletedContentPurger purger = new DeletedContentPurger(db, dbExecutor,
				taskScheduler, clock);
		eventBus.addListener(purger);
		return purger;
	}
}

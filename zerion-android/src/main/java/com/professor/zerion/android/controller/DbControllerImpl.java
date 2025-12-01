package com.professor.zerion.android.controller;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

@Immutable
@Deprecated
@NotNullByDefault
public class DbControllerImpl implements DbController {


	protected final Executor dbExecutor;
	private final LifecycleManager lifecycleManager;

	@Inject
	public DbControllerImpl(@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager) {
		this.dbExecutor = dbExecutor;
		this.lifecycleManager = lifecycleManager;
	}

	@Override
	public void runOnDbThread(Runnable task) {
		dbExecutor.execute(() -> {
			try {
				lifecycleManager.waitForDatabase();
				task.run();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}
}

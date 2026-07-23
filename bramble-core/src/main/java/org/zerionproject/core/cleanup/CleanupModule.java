package org.zerionproject.core.cleanup;

import org.zerionproject.core.api.cleanup.CleanupManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.LifecycleManager;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class CleanupModule {

	public static class EagerSingletons {
		@Inject
		CleanupManager cleanupManager;
	}

	@Provides
	@Singleton
	CleanupManager provideCleanupManager(LifecycleManager lifecycleManager,
			EventBus eventBus, CleanupManagerImpl cleanupManager) {
		lifecycleManager.registerService(cleanupManager);
		eventBus.addListener(cleanupManager);
		return cleanupManager;
	}
}

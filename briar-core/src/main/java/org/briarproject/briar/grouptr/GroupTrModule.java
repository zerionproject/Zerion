package org.briarproject.briar.grouptr;

import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.briar.api.grouptr.GroupTrManager;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class GroupTrModule {

	public static class EagerSingletons {
		@javax.inject.Inject
		GroupTrManager manager;
	}

	@Provides
	@Singleton
	GroupTrManager provideGroupTrManager(LifecycleManager lifecycleManager,
			GroupTrManagerImpl manager) {
		lifecycleManager.registerOpenDatabaseHook(manager);
		return manager;
	}
}

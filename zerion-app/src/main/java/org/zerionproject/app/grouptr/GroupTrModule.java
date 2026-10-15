package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.messaging.MessagingManager;

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
			MessagingManager messagingManager, GroupTrManagerImpl manager) {
		lifecycleManager.registerOpenDatabaseHook(manager);
		messagingManager.setInvitationCards(manager::invitationStillOpen);
		return manager;
	}
}

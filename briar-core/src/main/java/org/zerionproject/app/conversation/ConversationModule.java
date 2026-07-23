package org.zerionproject.app.conversation;

import org.zerionproject.app.api.conversation.ConversationManager;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class ConversationModule {

	public static class EagerSingletons {
		@Inject
		ConversationManager conversationManager;
	}

	@Provides
	@Singleton
	ConversationManager provideConversationManager(
			ConversationManagerImpl conversationManager) {
		return conversationManager;
	}
}

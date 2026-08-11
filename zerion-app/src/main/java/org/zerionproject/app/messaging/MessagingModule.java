package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FeatureFlags;
import org.zerionproject.core.api.cleanup.CleanupManager;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.validation.ValidationManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.VoiceSignalFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.zerionproject.app.api.messaging.MessagingManager.CLIENT_ID;
import static org.zerionproject.app.api.messaging.MessagingManager.MAJOR_VERSION;
import static org.zerionproject.app.api.messaging.MessagingManager.MINOR_VERSION;

@Module
public class MessagingModule {

	public static class EagerSingletons {
		@Inject
		MessagingManager messagingManager;
		@Inject
		PrivateMessageValidator privateMessageValidator;
	}

	@Provides
	PrivateMessageFactory providePrivateMessageFactory(
			PrivateMessageFactoryImpl privateMessageFactory) {
		return privateMessageFactory;
	}

	@Provides
	@Singleton
	VoiceSignalFactory provideVoiceSignalFactory(
			VoiceSignalFactoryImpl voiceSignalFactory) {
		return voiceSignalFactory;
	}

	@Provides
	@Singleton
	PrivateMessageValidator getValidator(ValidationManager validationManager,
			BdfReaderFactory bdfReaderFactory, MetadataEncoder metadataEncoder,
			Clock clock,
			org.zerionproject.core.api.crypto.CryptoComponent crypto) {
		PrivateMessageValidator validator = new PrivateMessageValidator(
				bdfReaderFactory, metadataEncoder, clock, crypto);
		validationManager.registerMessageValidator(CLIENT_ID, MAJOR_VERSION,
				validator);
		return validator;
	}

	@Provides
	@Singleton
	MessagingManager getMessagingManager(LifecycleManager lifecycleManager,
			ContactManager contactManager, ValidationManager validationManager,
			ConversationManager conversationManager,
			ClientVersioningManager clientVersioningManager,
			CleanupManager cleanupManager, FeatureFlags featureFlags,
			MessagingManagerImpl messagingManager) {
		lifecycleManager.registerOpenDatabaseHook(messagingManager);
		contactManager.registerContactHook(messagingManager);
		validationManager.registerIncomingMessageHook(CLIENT_ID, MAJOR_VERSION,
				messagingManager);
		conversationManager.registerConversationClient(messagingManager);
		boolean images = featureFlags.shouldEnableImageAttachments();
		boolean disappear = featureFlags.shouldEnableDisappearingMessages();
		int minorVersion = images ? (disappear ? MINOR_VERSION : 2) : 0;
		clientVersioningManager.registerClient(CLIENT_ID, MAJOR_VERSION,
				minorVersion, messagingManager);
		cleanupManager.registerCleanupHook(CLIENT_ID, MAJOR_VERSION,
				messagingManager);
		return messagingManager;
	}
}

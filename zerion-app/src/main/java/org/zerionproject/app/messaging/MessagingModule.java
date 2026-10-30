package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FeatureFlags;
import org.zerionproject.core.api.cleanup.CleanupManager;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.validation.ValidationManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.VoiceSignalFactory;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.zerionproject.app.api.messaging.MessagingManager.CLIENT_ID;
import static org.zerionproject.app.api.messaging.MessagingManager.MAJOR_VERSION;
import static org.zerionproject.app.api.messaging.MessagingManager.MINOR_VERSION;
import static org.zerionproject.app.api.messaging.MessagingManager.VIEW_ONCE_MIN_VERSION;

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

	static int announcedMinorVersion(FeatureFlags featureFlags) {
		if (!featureFlags.shouldEnableImageAttachments()) return 0;
		if (!featureFlags.shouldEnableDisappearingMessages()) return 2;
		if (!featureFlags.shouldEnableViewOnce()) {
			return Math.min(MINOR_VERSION, VIEW_ONCE_MIN_VERSION - 1);
		}
		return MINOR_VERSION;
	}

	@Provides
	@Singleton
	MessagingManager getMessagingManager(LifecycleManager lifecycleManager,
			ContactManager contactManager, ValidationManager validationManager,
			ConversationManager conversationManager,
			ClientVersioningManager clientVersioningManager,
			CleanupManager cleanupManager, FeatureFlags featureFlags,
			DatabaseComponent db, EventBus eventBus,
			@DatabaseExecutor Executor dbExecutor,
			MessagingManagerImpl messagingManager) {
		eventBus.addListener(new EditedOriginalEraser(db, dbExecutor,
				messagingManager));
		eventBus.addListener(new ViewOnceSweeper(db, dbExecutor,
				messagingManager));
		lifecycleManager.registerOpenDatabaseHook(messagingManager);
		contactManager.registerContactHook(messagingManager);
		validationManager.registerIncomingMessageHook(CLIENT_ID, MAJOR_VERSION,
				messagingManager);
		conversationManager.registerConversationClient(messagingManager);
		clientVersioningManager.registerClient(CLIENT_ID, MAJOR_VERSION,
				announcedMinorVersion(featureFlags), messagingManager);
		cleanupManager.registerCleanupHook(CLIENT_ID, MAJOR_VERSION,
				messagingManager);
		return messagingManager;
	}
}

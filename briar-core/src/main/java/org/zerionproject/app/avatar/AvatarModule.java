package org.zerionproject.app.avatar;

import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.validation.ValidationManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.app.api.avatar.AvatarManager;
import org.zerionproject.app.api.avatar.AvatarMessageEncoder;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.zerionproject.app.api.avatar.AvatarManager.CLIENT_ID;
import static org.zerionproject.app.api.avatar.AvatarManager.MAJOR_VERSION;
import static org.zerionproject.app.api.avatar.AvatarManager.MINOR_VERSION;

@Module
public class AvatarModule {

	public static class EagerSingletons {
		@Inject
		AvatarValidator avatarValidator;
		@Inject
		AvatarManager avatarManager;
	}

	@Provides
	@Singleton
	AvatarValidator provideAvatarValidator(ValidationManager validationManager,
			BdfReaderFactory bdfReaderFactory, MetadataEncoder metadataEncoder,
			Clock clock) {
		AvatarValidator avatarValidator =
				new AvatarValidator(bdfReaderFactory, metadataEncoder, clock);
		validationManager.registerMessageValidator(CLIENT_ID, MAJOR_VERSION,
				avatarValidator);
		return avatarValidator;
	}

	@Provides
	@Singleton
	AvatarMessageEncoder provideMessageEncoder(
			AvatarMessageEncoderImpl messageEncoder) {
		return messageEncoder;
	}

	@Provides
	@Singleton
	AvatarManager provideAvatarManager(
			LifecycleManager lifecycleManager,
			ContactManager contactManager,
			ValidationManager validationManager,
			ClientVersioningManager clientVersioningManager,
			AvatarManagerImpl avatarManager) {
		lifecycleManager.registerOpenDatabaseHook(avatarManager);
		contactManager.registerContactHook(avatarManager);
		validationManager.registerIncomingMessageHook(CLIENT_ID,
				MAJOR_VERSION, avatarManager);
		clientVersioningManager.registerClient(CLIENT_ID,
				MAJOR_VERSION, MINOR_VERSION, avatarManager);
		return avatarManager;
	}

}

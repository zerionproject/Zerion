package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.plugin.OnionClientAuthManager;
import org.zerionproject.core.api.sync.validation.ValidationManager;
import org.zerionproject.core.api.system.Clock;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.zerionproject.core.plugin.tor.auth.OnionAuthRecords.CLIENT_ID;
import static org.zerionproject.core.plugin.tor.auth.OnionAuthRecords.MAJOR_VERSION;

@Module
public class OnionAuthModule {

	public static class EagerSingletons {

		@Inject
		OnionAuthValidator onionAuthValidator;
		@Inject
		OnionClientAuthManager onionClientAuthManager;
	}

	@Provides
	@Singleton
	OnionAuthValidator getValidator(ValidationManager validationManager,
			ClientHelper clientHelper, MetadataEncoder metadataEncoder,
			Clock clock) {
		OnionAuthValidator validator = new OnionAuthValidator(clientHelper,
				metadataEncoder, clock);
		validationManager.registerMessageValidator(CLIENT_ID, MAJOR_VERSION,
				validator);
		return validator;
	}

	@Provides
	@Singleton
	OnionClientAuthManager getOnionClientAuthManager(
			LifecycleManager lifecycleManager,
			ValidationManager validationManager, ContactManager contactManager,
			EventBus eventBus,
			org.zerionproject.core.api.versioning.ClientVersioningManager
					clientVersioningManager,
			OnionClientAuthManagerImpl manager) {
		lifecycleManager.registerOpenDatabaseHook(manager);
		validationManager.registerIncomingMessageHook(CLIENT_ID, MAJOR_VERSION,
				manager);
		contactManager.registerContactHook(manager);
		clientVersioningManager.registerClient(CLIENT_ID, MAJOR_VERSION,
				OnionAuthRecords.MINOR_VERSION, manager);
		eventBus.addListener(manager);
		return manager;
	}
}

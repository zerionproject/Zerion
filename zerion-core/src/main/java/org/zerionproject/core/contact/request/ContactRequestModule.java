package org.zerionproject.core.contact.request;

import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.plugin.tor.InboxOnionAdapter;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class ContactRequestModule {

	public static class EagerSingletons {
		@Inject
		ContactRequestManager contactRequestManager;
	}

	@Provides
	@Singleton
	InboxTransportProvider provideInboxTransportProvider(
			PluginManager pluginManager) {
		return () -> {
			Plugin p = pluginManager.getPlugin(TorConstants.ID);
			return p instanceof InboxOnionAdapter ? (InboxOnionAdapter) p
					: null;
		};
	}

	@Provides
	@Singleton
	ContactRequestManager provideContactRequestManager(
			LifecycleManager lifecycleManager, EventBus eventBus,
			ContactManager contactManager, ContactRequestSender sender,
			InboxService inboxService, ContactRequestManagerImpl manager) {
		lifecycleManager.registerService(sender);
		eventBus.addListener(sender);
		lifecycleManager.registerService(inboxService);
		eventBus.addListener(inboxService);
		lifecycleManager.registerService(manager);
		contactManager.registerPendingContactHook(manager);
		return manager;
	}
}

package org.zerionproject.app.messaging;

import org.zerionproject.core.BrambleCoreIntegrationTestEagerSingletons;
import org.zerionproject.core.BrambleCoreModule;
import org.zerionproject.core.api.cleanup.CleanupManager;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.db.DatabaseModule;
import org.zerionproject.core.test.BrambleCoreIntegrationTestModule;
import org.zerionproject.core.test.TestDnsModule;
import org.zerionproject.core.test.TestPluginConfigModule;
import org.zerionproject.core.test.TestSocksModule;
import org.zerionproject.core.test.TimeTravel;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.autodelete.AutoDeleteModule;
import org.zerionproject.app.client.BriarClientModule;
import org.zerionproject.app.conversation.ConversationModule;

import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component(modules = {
		AutoDeleteModule.class,
		BrambleCoreIntegrationTestModule.class,
		BrambleCoreModule.class,
		org.zerionproject.transport.ZerionTransportModule.class,
		DatabaseModule.class,
		BriarClientModule.class,
		ConversationModule.class,
		MessagingModule.class,
		TestDnsModule.class,
		TestSocksModule.class,
		TestPluginConfigModule.class,
})
interface ContactGroupScanTestComponent
		extends BrambleCoreIntegrationTestEagerSingletons {

	void inject(MessagingModule.EagerSingletons init);

	LifecycleManager getLifecycleManager();

	IdentityManager getIdentityManager();

	ContactManager getContactManager();

	MessagingManager getMessagingManager();

	ClientHelper getClientHelper();

	DatabaseComponent getDatabaseComponent();

	ClientVersioningManager getClientVersioningManager();

	MetadataParser getMetadataParser();

	ConversationManager getConversationManager();

	MessageTracker getMessageTracker();

	ContactGroupFactory getContactGroupFactory();

	AutoDeleteManager getAutoDeleteManager();

	StreamingAttachmentWriter getStreamingAttachmentWriter();

	PrivateMessageValidator getPrivateMessageValidator();

	CleanupManager getCleanupManager();

	EventBus getEventBus();

	TimeTravel getTimeTravel();

	CryptoComponent getCryptoComponent();

	class Helper {

		public static void injectEagerSingletons(
				ContactGroupScanTestComponent c) {
			BrambleCoreIntegrationTestEagerSingletons.Helper
					.injectEagerSingletons(c);
			c.inject(new MessagingModule.EagerSingletons());
		}
	}
}

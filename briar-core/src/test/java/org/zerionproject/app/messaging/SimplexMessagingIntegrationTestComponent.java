package org.zerionproject.app.messaging;

import org.zerionproject.core.BrambleCoreIntegrationTestEagerSingletons;
import org.zerionproject.core.BrambleCoreModule;
import org.zerionproject.core.db.DatabaseModule;
import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.test.BrambleCoreIntegrationTestModule;
import org.zerionproject.core.test.TestDnsModule;
import org.zerionproject.core.test.TestPluginConfigModule;
import org.zerionproject.core.test.TestSocksModule;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
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
		DatabaseModule.class,
		BriarClientModule.class,
		ConversationModule.class,
		MessagingModule.class,
		TestDnsModule.class,
		TestSocksModule.class,
		TestPluginConfigModule.class,
})
interface SimplexMessagingIntegrationTestComponent
		extends BrambleCoreIntegrationTestEagerSingletons {

	void inject(MessagingModule.EagerSingletons init);

	LifecycleManager getLifecycleManager();

	IdentityManager getIdentityManager();

	ContactManager getContactManager();

	MessagingManager getMessagingManager();

	PrivateMessageFactory getPrivateMessageFactory();

	EventBus getEventBus();

	ConnectionManager getConnectionManager();

	class Helper {

		public static void injectEagerSingletons(
				SimplexMessagingIntegrationTestComponent c) {
			BrambleCoreIntegrationTestEagerSingletons.Helper
					.injectEagerSingletons(c);
			c.inject(new MessagingModule.EagerSingletons());
		}
	}
}

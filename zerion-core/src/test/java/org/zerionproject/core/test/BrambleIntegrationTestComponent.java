package org.zerionproject.core.test;

import org.zerionproject.core.BrambleCoreIntegrationTestEagerSingletons;
import org.zerionproject.core.BrambleCoreModule;
import org.zerionproject.core.db.DatabaseModule;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;

import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component(modules = {
		BrambleCoreIntegrationTestModule.class,
		BrambleCoreModule.class,
		DatabaseModule.class,
		TestDnsModule.class,
		TestSocksModule.class,
		TestPluginConfigModule.class,
})
public interface BrambleIntegrationTestComponent
		extends BrambleCoreIntegrationTestEagerSingletons {

	IdentityManager getIdentityManager();

	EventBus getEventBus();

	ConnectionManager getConnectionManager();

	ClientHelper getClientHelper();

}

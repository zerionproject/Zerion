package org.zerionproject.core.contact;

import org.zerionproject.core.BrambleCoreIntegrationTestEagerSingletons;
import org.zerionproject.core.BrambleCoreModule;
import org.zerionproject.core.db.DatabaseModule;
import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.contact.ContactExchangeManager;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.test.BrambleCoreIntegrationTestModule;
import org.zerionproject.core.test.TestDnsModule;
import org.zerionproject.core.test.TestPluginConfigModule;
import org.zerionproject.core.test.TestSocksModule;

import java.util.concurrent.Executor;

import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component(modules = {
		BrambleCoreIntegrationTestModule.class,
		BrambleCoreModule.class,
		org.zerionproject.transport.ZerionTransportModule.class,
		DatabaseModule.class,
		TestDnsModule.class,
		TestSocksModule.class,
		TestPluginConfigModule.class,
})
interface ContactExchangeIntegrationTestComponent
		extends BrambleCoreIntegrationTestEagerSingletons {

	ConnectionManager getConnectionManager();

	org.zerionproject.core.api.client.ClientHelper getClientHelper();

	ContactExchangeCrypto getContactExchangeCrypto();

	org.zerionproject.core.api.record.RecordReaderFactory getRecordReaderFactory();

	org.zerionproject.core.api.record.RecordWriterFactory getRecordWriterFactory();

	org.zerionproject.core.api.transport.StreamReaderFactory getStreamReaderFactory();

	org.zerionproject.core.api.transport.StreamWriterFactory getStreamWriterFactory();

	ContactExchangeManager getContactExchangeManager();

	ContactManager getContactManager();

	EventBus getEventBus();

	IdentityManager getIdentityManager();

	@IoExecutor
	Executor getIoExecutor();

	LifecycleManager getLifecycleManager();
}

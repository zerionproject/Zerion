package org.zerionproject.core.transport.agreement;

import org.zerionproject.core.BrambleCoreModule;
import org.zerionproject.core.db.DatabaseModule;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.test.BrambleCoreIntegrationTestModule;
import org.zerionproject.core.test.BrambleIntegrationTestComponent;
import org.zerionproject.core.test.TestDnsModule;
import org.zerionproject.core.test.TestPluginConfigModule;
import org.zerionproject.core.test.TestSocksModule;

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
interface TransportKeyAgreementTestComponent
		extends BrambleIntegrationTestComponent {

	KeyManager getKeyManager();

	TransportKeyAgreementManagerImpl getTransportKeyAgreementManager();

	ContactManager getContactManager();

	LifecycleManager getLifecycleManager();

	ContactGroupFactory getContactGroupFactory();

	SessionParser getSessionParser();

	TransportPropertyManager getTransportPropertyManager();

	DatabaseComponent getDatabaseComponent();
}

package org.zerionproject.core.sync;

import org.zerionproject.core.BrambleCoreIntegrationTestEagerSingletons;
import org.zerionproject.core.BrambleCoreModule;
import org.zerionproject.core.db.DatabaseModule;
import org.zerionproject.core.test.BrambleCoreIntegrationTestModule;
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
interface SyncIntegrationTestComponent extends
		BrambleCoreIntegrationTestEagerSingletons {

	void inject(SyncIntegrationTest testCase);
}

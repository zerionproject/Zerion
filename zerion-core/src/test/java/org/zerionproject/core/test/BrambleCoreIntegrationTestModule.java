package org.zerionproject.core.test;

import org.zerionproject.core.account.AccountModule;
import org.zerionproject.core.battery.DefaultBatteryManagerModule;
import org.zerionproject.core.event.DefaultEventExecutorModule;
import org.zerionproject.core.system.DefaultWakefulIoExecutorModule;
import org.zerionproject.core.system.TimeTravelModule;

import dagger.Module;

@Module(includes = {
		AccountModule.class,
		DefaultBatteryManagerModule.class,
		DefaultEventExecutorModule.class,
		DefaultWakefulIoExecutorModule.class,
		TestThreadFactoryModule.class,
		TestDatabaseConfigModule.class,
		TestFeatureFlagModule.class,
		TestSecureRandomModule.class,
		TimeTravelModule.class
})
public class BrambleCoreIntegrationTestModule {

}

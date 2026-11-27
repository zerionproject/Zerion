package org.zerionproject.core.test;

import org.zerionproject.core.account.AccountModule;
import org.zerionproject.core.battery.DefaultBatteryManagerModule;
import org.zerionproject.core.event.DefaultEventExecutorModule;
import org.zerionproject.core.system.DefaultWakefulIoExecutorModule;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.system.TimeTravelModule;
import org.zerionproject.tor.TorWakePolicy;

import java.util.concurrent.Executor;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

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

	@Provides
	@Singleton
	TorWakePolicy provideTorWakePolicy(TaskScheduler taskScheduler,
			@IoExecutor Executor ioExecutor) {
		return new TorWakePolicy(taskScheduler, ioExecutor);
	}
}

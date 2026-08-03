package org.zerionproject.core.battery;

import org.zerionproject.core.api.battery.BatteryManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class AndroidBatteryModule {

	public static class EagerSingletons {
		@Inject
		BatteryManager batteryManager;
	}

	@Provides
	@Singleton
	BatteryManager provideBatteryManager(LifecycleManager lifecycleManager,
			AndroidBatteryManager batteryManager) {
		lifecycleManager.registerService(batteryManager);
		return batteryManager;
	}
}

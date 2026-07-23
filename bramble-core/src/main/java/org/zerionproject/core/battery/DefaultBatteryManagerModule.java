package org.zerionproject.core.battery;

import org.zerionproject.core.api.battery.BatteryManager;

import dagger.Module;
import dagger.Provides;

@Module
public class DefaultBatteryManagerModule {

	@Provides
	BatteryManager provideBatteryManager() {
		return () -> false;
	}
}

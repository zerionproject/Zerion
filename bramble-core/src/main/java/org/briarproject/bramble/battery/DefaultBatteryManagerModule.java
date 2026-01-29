package org.briarproject.bramble.battery;

import org.briarproject.bramble.api.battery.BatteryManager;

import dagger.Module;
import dagger.Provides;


@Module
public class DefaultBatteryManagerModule {

	@Provides
	BatteryManager provideBatteryManager() {
		return () -> false;
	}
}

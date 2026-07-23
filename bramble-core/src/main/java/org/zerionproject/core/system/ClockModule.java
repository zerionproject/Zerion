package org.zerionproject.core.system;

import org.zerionproject.core.api.system.Clock;

import dagger.Module;
import dagger.Provides;

@Module
public class ClockModule {

	@Provides
	Clock provideClock() {
		return new SystemClock();
	}
}

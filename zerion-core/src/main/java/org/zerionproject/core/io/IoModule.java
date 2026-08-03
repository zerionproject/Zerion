package org.zerionproject.core.io;

import org.zerionproject.core.api.io.TimeoutMonitor;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class IoModule {

	@Provides
	@Singleton
	TimeoutMonitor provideTimeoutMonitor(TimeoutMonitorImpl timeoutMonitor) {
		return timeoutMonitor;
	}
}

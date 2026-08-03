package org.zerionproject.app.client;

import org.zerionproject.app.api.client.MessageTracker;

import dagger.Module;
import dagger.Provides;

@Module
public class BriarClientModule {

	@Provides
	MessageTracker provideMessageTracker(MessageTrackerImpl messageTracker) {
		return messageTracker;
	}
}

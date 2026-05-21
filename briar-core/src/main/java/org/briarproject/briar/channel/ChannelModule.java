package org.briarproject.briar.channel;

import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.ChannelTransport;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class ChannelModule {

	public static class EagerSingletons {
		@javax.inject.Inject
		ChannelManager manager;
	}

	@Provides
	@Singleton
	ChannelManager provideChannelManager(LifecycleManager lifecycleManager,
			ChannelManagerImpl manager) {
		lifecycleManager.registerOpenDatabaseHook(manager);
		return manager;
	}

	@Provides
	@Singleton
	OnionPublisher provideOnionPublisher(
			TorPluginOnionPublisher impl) {
		return impl;
	}

	@Provides
	@Singleton
	ChannelTransport provideChannelTransport(
			TorChannelTransport impl) {
		return impl;
	}
}

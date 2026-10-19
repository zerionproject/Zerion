package com.professor.zerion.android.settings;

import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.plugin.tor.auth.OnionAuthDebugHooks;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class LifecycleTestsModule {

	@Provides
	@Singleton
	OnionAuthDebugHooks provideOnionAuthDebugHooks(
			SettingsManager settingsManager) {
		return new LifecycleSwitches(settingsManager);
	}
}

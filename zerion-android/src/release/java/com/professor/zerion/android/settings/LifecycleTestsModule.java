package com.professor.zerion.android.settings;

import org.zerionproject.core.plugin.tor.auth.OnionAuthDebugHooks;

import dagger.Module;
import dagger.Provides;

@Module
public class LifecycleTestsModule {

	@Provides
	OnionAuthDebugHooks provideOnionAuthDebugHooks() {
		return OnionAuthDebugHooks.NONE;
	}
}

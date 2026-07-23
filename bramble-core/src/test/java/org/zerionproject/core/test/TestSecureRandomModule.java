package org.zerionproject.core.test;

import org.zerionproject.core.api.system.SecureRandomProvider;

import dagger.Module;
import dagger.Provides;

@Module
public class TestSecureRandomModule {

	@Provides
	SecureRandomProvider provideSecureRandomProvider() {
		return new TestSecureRandomProvider();
	}
}

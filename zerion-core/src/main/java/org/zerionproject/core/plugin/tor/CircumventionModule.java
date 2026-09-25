package org.zerionproject.core.plugin.tor;

import org.zerionproject.tor.CircumventionProvider;
import org.zerionproject.tor.CircumventionProviderFactory;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class CircumventionModule {

	@Provides
	@Singleton
	CircumventionProvider provideCircumventionProvider() {
		return CircumventionProviderFactory.createCircumventionProvider();
	}
}

package org.zerionproject.core.test;

import org.zerionproject.core.api.FeatureFlags;

import dagger.Module;
import dagger.Provides;

@Module
public class TestFeatureFlagModule {
	@Provides
	public FeatureFlags provideFeatureFlags() {
		return new FeatureFlags() {
			@Override
			public boolean shouldEnableImageAttachments() {
				return true;
			}

			@Override
			public boolean shouldEnableProfilePictures() {
				return true;
			}

			@Override
			public boolean shouldEnableDisappearingMessages() {
				return true;
			}

			@Override
			public boolean shouldEnableI2p() {
				return false;
			}

			@Override
			public boolean shouldEnableDebugCountryOverride() {
				return false;
			}
		};
	}
}

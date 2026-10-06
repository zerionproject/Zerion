package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class PcsModule {

	public static class EagerSingletons {
		@Inject
		PcsStateManager pcsStateManager;
	}

	@Provides
	PcsRatchet providePcsRatchet(PcsRatchetImpl pcsRatchet) {
		return pcsRatchet;
	}

	@Provides
	PcsHeaderCodec providePcsHeaderCodec() {
		return new PcsHeaderCodec();
	}

	@Provides
	@Singleton
	MlKemProvider provideMlKemProvider(MlKemProviderImpl provider) {
		return provider;
	}

	@Provides
	@Singleton
	Mode3FullRatchet provideMode3FullRatchet(Mode3FullRatchetImpl ratchet) {
		return ratchet;
	}
}

package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PqRatchet;
import org.zerionproject.core.api.crypto.pcs.SkippedKeyStore;

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
	@Singleton
	SkippedKeyStore provideSkippedKeyStore(DatabaseSkippedKeyStore store) {
		return store;
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
	PqRatchet providePqRatchet(PqRatchetImpl pqRatchet) {
		return pqRatchet;
	}

	@Provides
	@Singleton
	Mode3FullRatchet provideMode3FullRatchet(Mode3FullRatchetImpl ratchet) {
		return ratchet;
	}

	@Provides
	@Singleton
	ChunkingManager provideChunkingManager(MlKemProvider mlKemProvider) {
		return new ChunkingManager(mlKemProvider);
	}
}

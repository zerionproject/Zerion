package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.crypto.pcs.MlKemProvider;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.PqRatchet;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class PcsModule {

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
	ChunkingManager provideChunkingManager() {
		return new ChunkingManager();
	}
}
